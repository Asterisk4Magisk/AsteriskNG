package features.logs

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal object AndroidCoreLogRepository : AndroidXrayLogRepository(
    logFile = { context -> context.androidXrayErrorLog() },
    logTag = "AndroidCoreLogRepository",
)

internal object AndroidAccessLogRepository : AndroidXrayLogRepository(
    logFile = { context -> context.androidXrayAccessLog() },
    logTag = "AndroidAccessLogRepository",
)

internal abstract class AndroidXrayLogRepository(
    private val logFile: (Context) -> CoreLogFile,
    private val logTag: String,
) : InMemoryCoreLogRepository() {
    private val restoredPreviousLogs = AtomicBoolean(false)
    private var appContext: Context? = null
    private val fileStore = BoundedLogFileStore(
        file = { appContext?.let(logFile)?.let { File(it.path) } },
        logTag = logTag,
        onFailure = { message, error -> AndroidAppLogger.warn(logTag, message, error) },
    )

    fun initialize(context: Context) {
        appContext = context.applicationContext
        restorePreviousLogs()
    }

    override fun clear() {
        super.clear()
        fileStore.clear()
    }

    override suspend fun refresh() {
        val context = appContext ?: return
        val restoredLines = withContext(Dispatchers.IO) {
            readRestoredLines(context)
        }
        replaceRestoredLines(restoredLines)
    }

    private fun restorePreviousLogs() {
        val context = appContext ?: return
        if (!restoredPreviousLogs.compareAndSet(false, true) || entries.value.isNotEmpty()) {
            return
        }

        replaceRestoredLines(readRestoredLines(context))
    }

    private fun readRestoredLines(context: Context): List<RestoredCoreLogLine> {
        var order = 0L
        val file = logFile(context)
        return fileStore
            .readLastLines()
            .mapNotNull { line ->
                parseCoreLogLine(line, file.defaultLevel)?.let { parsedLine ->
                    RestoredCoreLogLine(
                        order = order++,
                        parsedLine = parsedLine,
                    )
                }
            }
            .sortedWith(
                compareBy<RestoredCoreLogLine> { it.parsedLine.time.orEmpty() }
                    .thenBy { it.order },
            )
            .takeLast(MaxEntries)
    }

    private fun replaceRestoredLines(restoredLines: List<RestoredCoreLogLine>) {
        replaceEntries(
            restoredLines.mapIndexed { index, restoredLine ->
                val parsedLine = restoredLine.parsedLine
                CoreLogEntry(
                    id = index + 1L,
                    time = parsedLine.time ?: currentLogTime(),
                    level = parsedLine.level,
                    message = parsedLine.message,
                )
            }
        )
    }
}

private data class RestoredCoreLogLine(
    val order: Long,
    val parsedLine: ParsedCoreLogLine,
)

private const val MaxEntries = CoreLogMaxEntries
