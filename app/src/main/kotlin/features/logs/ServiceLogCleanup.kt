// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.logs

import java.io.File

internal fun clearServiceLogsAsApp(logDirectory: File, logTag: String) {
    runCatching { clearServiceLogRepositories() }.onFailure { error ->
        runCatching { AndroidAppLogger.warn(logTag, "Failed to clear in-memory service logs", error) }
    }
    clearServiceLogDirectoryBestEffort(logDirectory) { file, error ->
        AndroidAppLogger.warn(logTag, "Failed to clear service log: ${file.absolutePath}", error)
    }
}

internal fun clearServiceLogRepositories() {
    AndroidCoreLogRepository.clearInMemory()
    AndroidAccessLogRepository.clearInMemory()
    AndroidAsteriskdLogRepository.clearInMemory()
}

internal fun clearServiceLogDirectoryBestEffort(
    logDirectory: File,
    onFailure: (File, Throwable) -> Unit,
) {
    val entries = runCatching {
        if (!logDirectory.exists()) return
        require(logDirectory.isDirectory) { "Service log path is not a directory" }
        logDirectory.listFiles() ?: error("Failed to list service log directory")
    }.getOrElse { error ->
        reportCleanupFailure(logDirectory, error, onFailure)
        return
    }
    entries
        .filterNot { file -> file.name == LogcatFileName }
        .forEach { file ->
            runCatching {
                require(
                    file.isFile &&
                        file.absoluteFile.parentFile == logDirectory.absoluteFile &&
                        file.canonicalFile.parentFile == logDirectory.canonicalFile &&
                        file.canonicalFile.name == file.name,
                ) {
                    "Service log entry is not a regular direct file"
                }
                if (!file.delete()) {
                    file.writeText("")
                }
            }.onFailure { error -> reportCleanupFailure(file, error, onFailure) }
        }
}

private fun reportCleanupFailure(
    file: File,
    error: Throwable,
    onFailure: (File, Throwable) -> Unit,
) {
    runCatching { onFailure(file, error) }
}

private const val LogcatFileName = "logcat.log"
