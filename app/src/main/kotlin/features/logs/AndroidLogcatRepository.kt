// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.logs

import android.content.Context
import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import utils.decodeBase64StringOrNull
import utils.encodeBase64
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's own log, persisted to `logcat.log`.
 *
 * The app process and the VPN Service process both append to that one file, each line under an
 * exclusive lock of the file, so the file stays in write order. The VPN Service process only
 * appends; the app process shows the file and follows the other process' lines through inotify.
 */
internal object AndroidLogcatRepository : InMemoryCoreLogRepository() {
    private const val LogTag = "AndroidLogcatRepository"

    /** Folds a burst of lines from the VPN Service process into one read. */
    private const val MinFollowIntervalMillis = 250L

    private val restoredPreviousLogs = AtomicBoolean(false)
    private var appContext: Context? = null

    /** Whether this is the VPN Service process, which appends to the file but never shows it. */
    @Volatile
    private var serviceProcess = false
    private val logFile = SharedLogFile(
        file = { appContext?.androidAppLogcatFile() },
        onFailure = { message, error -> AndroidAppLogger.platformWarn(LogTag, message, error) },
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Writes the VPN Service process' lines in order without ever blocking a caller on the file
     * lock, which the app process may hold.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val serviceProcessWriter = Dispatchers.IO.limitedParallelism(1)

    fun initialize(context: Context) {
        appContext = context.applicationContext
        restorePreviousLogs()
        LogDirectoryWatcher.watchCoalesced(
            file = context.applicationContext.androidAppLogcatFile(),
            mask = FileObserver.CLOSE_WRITE,
            scope = scope,
            minIntervalMillis = MinFollowIntervalMillis,
            onChange = ::showLinesOfOtherProcesses,
        )
    }

    /** Appends this process' log to the shared file; the app process shows it. */
    fun initializeServiceProcess(context: Context) {
        serviceProcess = true
        appContext = context.applicationContext
        if (!restoredPreviousLogs.compareAndSet(false, true)) return
        entries.value.forEach { entry ->
            persist(encodeLogLine(entry.time, entry.level, entry.message))
        }
    }

    override fun append(level: String, message: String, time: String) {
        persist(encodeLogLine(time, level, message))
        super.append(level, message, time)
    }

    override fun clear() {
        super.clear()
        logFile.clear()
    }

    override suspend fun refresh() {
        val restoredEntries = withContext(Dispatchers.IO) {
            readPersistedEntries()
        }
        replaceEntries(restoredEntries)
    }

    private fun persist(line: String) {
        if (serviceProcess) {
            scope.launch(serviceProcessWriter) { logFile.append(line) }
            return
        }
        // Lines the other process appended first are shown before this one.
        showLines(logFile.append(line))
    }

    private suspend fun showLinesOfOtherProcesses() {
        showLines(logFile.readNewLines())
    }

    /** Shows lines read from the file; null means it was rewritten and is read again. */
    private fun showLines(lines: List<String>?) {
        if (lines == null) {
            replaceEntries(readPersistedEntries())
            return
        }
        lines.mapNotNull(::decodeLogLine).forEach { entry ->
            // Already persisted, so only shown here.
            super.append(entry.level, entry.message, entry.time)
        }
    }

    private fun restorePreviousLogs() {
        if (!restoredPreviousLogs.compareAndSet(false, true)) {
            return
        }
        val pendingEntries = entries.value
        replaceEntries(readPersistedEntries() + pendingEntries)
        pendingEntries.forEach { entry ->
            logFile.append(encodeLogLine(entry.time, entry.level, entry.message))
        }
    }

    private fun readPersistedEntries(): List<CoreLogEntry> {
        return logFile.readAllLines()
            .mapIndexedNotNull { index, line ->
                decodeLogLine(line)?.copy(id = index + 1L)
            }
    }
}

/**
 * A bounded log file that several processes append to.
 *
 * Every access opens the file, takes an exclusive lock and works through that one descriptor:
 * locks belong to the process, and closing any other descriptor of the file would drop them.
 * The process that shows the file remembers how much of it it has read, so it can tell lines
 * of other processes from its own.
 */
private class SharedLogFile(
    private val file: () -> File?,
    private val maxLines: Int = CoreLogMaxEntries,
    private val trimExtraLines: Int = LogFileTrimExtraLines,
    private val onFailure: (message: String, error: Throwable) -> Unit,
) {
    private val lock = Any()
    private var lineCount = 0

    /** How much of the file this process has read or written. */
    private var knownLength = 0L

    /**
     * Appends [line] and returns the lines other processes appended since this process last
     * looked, or null when the file was rewritten meanwhile and has to be read again.
     */
    fun append(line: String): List<String>? = locked(fallback = emptyList()) { access ->
        val others = linesSinceKnown(access)
        access.seek(access.length())
        access.write("$line\n".toByteArray(Charsets.UTF_8))
        lineCount += 1 + (others?.size ?: 0)
        if (lineCount > maxLines + trimExtraLines) trim(access)
        knownLength = access.length()
        others
    }

    /** Lines other processes appended since this process last looked; null after a rewrite. */
    fun readNewLines(): List<String>? = locked(fallback = emptyList(), write = false) { access ->
        linesSinceKnown(access).also { others ->
            lineCount += others?.size ?: 0
            knownLength = access.length()
        }
    }

    fun readAllLines(): List<String> = locked(fallback = emptyList(), write = false) { access ->
        lastLines(access).also { lines ->
            lineCount = lines.size
            knownLength = access.length()
        }
    }

    fun clear() {
        locked(fallback = Unit) { access ->
            access.setLength(0L)
            lineCount = 0
            knownLength = 0L
        }
    }

    private fun linesSinceKnown(access: RandomAccessFile): List<String>? {
        val length = access.length()
        if (length < knownLength) return null
        if (length == knownLength) return emptyList()
        return readLines(access, from = knownLength, to = length)
    }

    private fun trim(access: RandomAccessFile) {
        val lines = lastLines(access)
        access.setLength(0L)
        access.write(lines.joinToString(separator = "") { line -> "$line\n" }.toByteArray(Charsets.UTF_8))
        lineCount = lines.size
    }

    private fun lastLines(access: RandomAccessFile): List<String> {
        return readLines(access, from = 0L, to = access.length()).takeLast(maxLines)
    }

    private fun readLines(access: RandomAccessFile, from: Long, to: Long): List<String> {
        val bytes = ByteArray((to - from).toInt())
        access.seek(from)
        access.readFully(bytes)
        return String(bytes, Charsets.UTF_8).lineSequence().filter(String::isNotEmpty).toList()
    }

    /**
     * Runs [block] under the file lock. Reads use a read-only descriptor and a shared lock: a
     * descriptor opened for writing reports CLOSE_WRITE when it closes, which would wake the
     * follower for its own read.
     */
    private fun <T> locked(fallback: T, write: Boolean = true, block: (RandomAccessFile) -> T): T = synchronized(lock) {
        val logFile = file() ?: return fallback
        if (!write && !logFile.isFile) return fallback
        runCatching {
            logFile.parentFile?.mkdirs()
            RandomAccessFile(logFile, if (write) "rw" else "r").use { access ->
                val fileLock = access.channel.lock(0L, Long.MAX_VALUE, !write)
                try {
                    block(access)
                } finally {
                    fileLock.release()
                }
            }
        }.getOrElse { error ->
            onFailure("Failed to access log file: ${logFile.absolutePath}", error)
            fallback
        }
    }
}

private const val LogcatFieldSeparator = '\t'

private fun encodeLogLine(time: String, level: String, message: String): String {
    return listOf(time, level, message.encodeBase64()).joinToString(LogcatFieldSeparator.toString())
}

private fun decodeLogLine(line: String): CoreLogEntry? {
    val fields = line.split(LogcatFieldSeparator, limit = 3)
    if (fields.size != 3) {
        return null
    }
    val message = fields[2].decodeBase64StringOrNull() ?: return null
    return CoreLogEntry(
        id = 0L,
        time = fields[0],
        level = fields[1],
        message = message,
    )
}
