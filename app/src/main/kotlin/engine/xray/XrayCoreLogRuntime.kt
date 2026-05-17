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
    AndroidCoreLogRepository.clear()
    AndroidAccessLogRepository.clear()
    listOf(accessLogFile(), errorLogFile())
        .filter { logFile -> logFile.path.isNotBlank() }
        .forEach { logFile ->
            runCatching {
                File(logFile.path).apply {
                    parentFile?.mkdirs()
                    writeText("")
                }
            }.onFailure { error ->
                AndroidAppLogger.warn(logTag, "Failed to clear xray log file: ${logFile.path}", error)
            }
        }
}

private fun XrayCoreLogPaths.accessLogFile(): CoreLogFile {
    return CoreLogFile(path = accessLogPath, defaultLevel = "info")
}

private fun XrayCoreLogPaths.errorLogFile(): CoreLogFile {
    return CoreLogFile(path = errorLogPath, defaultLevel = "error")
}
