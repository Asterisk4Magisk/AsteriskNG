package features.logs

import java.io.File

internal class BoundedLogFileStore(
    private val file: () -> File?,
    private val logTag: String,
    private val maxLines: Int = CoreLogMaxEntries,
    private val trimExtraLines: Int = LogFileTrimExtraLines,
    private val onFailure: (message: String, error: Throwable) -> Unit,
) {
    private val lock = Any()
    private var lineCount = 0

    fun appendLine(line: String) {
        val logFile = file() ?: return
        synchronized(lock) {
            runCatching {
                logFile.parentFile?.mkdirs()
                logFile.appendText("$line\n")
                lineCount += 1
                if (lineCount > maxLines + trimExtraLines) {
                    trimLocked(logFile)
                }
            }.onFailure { error ->
                reportFailure("append to", logFile, error)
            }
        }
    }

    fun clear() {
        val logFile = file() ?: return
        synchronized(lock) {
            runCatching {
                logFile.parentFile?.mkdirs()
                logFile.writeText("")
                lineCount = 0
            }.onFailure { error ->
                reportFailure("clear", logFile, error)
            }
        }
    }

    fun readLastLines(): List<String> {
        val logFile = file() ?: return emptyList()
        val lines = synchronized(lock) {
            readLastLinesLocked(logFile)
        }
        lineCount = lines.size
        return lines
    }

    private fun trimLocked(logFile: File) {
        val lines = readLastLinesLocked(logFile)
        logFile.writeText(
            lines.joinToString(separator = "\n", postfix = if (lines.isEmpty()) "" else "\n"),
        )
        lineCount = lines.size
    }

    private fun readLastLinesLocked(logFile: File): List<String> {
        if (!logFile.exists() || !logFile.isFile || maxLines <= 0) {
            return emptyList()
        }

        return runCatching {
            val lines = ArrayDeque<String>(maxLines)
            logFile.forEachLine(Charsets.UTF_8) { line ->
                if (lines.size == maxLines) {
                    lines.removeFirst()
                }
                lines.addLast(line)
            }
            lines.toList()
        }.onFailure { error ->
            reportFailure("read", logFile, error)
        }.getOrDefault(emptyList())
    }

    private fun reportFailure(action: String, logFile: File, error: Throwable) {
        onFailure("Failed to $action $logTag file: ${logFile.absolutePath}", error)
    }
}
