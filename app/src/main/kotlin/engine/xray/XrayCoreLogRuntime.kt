package engine.xray

import features.logs.AndroidAccessLogRepository
import features.logs.AndroidAppLogger
import features.logs.AndroidCoreLogRepository
import features.logs.CoreLogFile
import features.logs.CoreLogFileTailer
import java.io.File

internal fun XrayCoreLogPaths.startCoreLogTailers(enableAccessLog: Boolean): List<CoreLogFileTailer> {
    return buildList {
        add(
            CoreLogFileTailer(
                logFiles = listOf(errorLogFile()),
                repository = AndroidCoreLogRepository,
            ),
        )
        if (enableAccessLog) {
            add(
                CoreLogFileTailer(
                    logFiles = listOf(accessLogFile()),
                    repository = AndroidAccessLogRepository,
                ),
            )
        }
    }.onEach { tailer -> tailer.start() }
}

internal fun XrayCoreLogPaths.clearCoreLogs(logTag: String) {
    clearCoreLogRepositories()
    clearCoreLogFilesAsApp(
        logPaths = logFilePaths(),
        logTag = logTag,
    )
}

internal fun clearCoreLogRepositories() {
    AndroidCoreLogRepository.clear()
    AndroidAccessLogRepository.clear()
}

internal fun XrayCoreLogPaths.logFilePaths(): List<String> {
    return listOf(accessLogPath, errorLogPath).filter(String::isNotBlank)
}

internal fun clearCoreLogFilesAsApp(logPaths: List<String>, logTag: String) {
    logPaths
        .filter(String::isNotBlank)
        .forEach { logPath ->
            runCatching {
                File(logPath).apply {
                    parentFile?.mkdirs()
                    writeText("")
                }
            }.onFailure { error ->
                AndroidAppLogger.warn(logTag, "Failed to clear xray log file: $logPath", error)
            }
        }
}

private fun XrayCoreLogPaths.accessLogFile(): CoreLogFile {
    return CoreLogFile(path = accessLogPath, defaultLevel = "info")
}

private fun XrayCoreLogPaths.errorLogFile(): CoreLogFile {
    return CoreLogFile(path = errorLogPath, defaultLevel = "error")
}
