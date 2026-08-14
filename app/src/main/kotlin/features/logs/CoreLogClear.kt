// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.logs

import android.content.Context
import engine.xray.XrayCoreLogPaths
import engine.xray.clearCoreLogFilesAsApp
import engine.xray.prepareXrayCoreLogPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import engine.root.runtime.rootAsteriskdLogPath
import java.io.File

internal suspend fun Context.clearCoreLogFile(logFile: XrayLogFile) {
    val logPath = applicationContext.prepareXrayCoreLogPaths().pathOf(logFile)
    if (logPath.isBlank()) {
        return
    }

    withContext(Dispatchers.IO) {
        clearCoreLogFilesAsApp(
            logPaths = listOf(logPath),
            logTag = LogTag,
        )
    }
}

internal suspend fun Context.clearAsteriskdLogFile() {
    val logFile = File(applicationContext.rootAsteriskdLogPath())
    if (!logFile.exists()) return
    withContext(Dispatchers.IO) {
        require(logFile.isFile && logFile.canonicalFile == logFile.absoluteFile)
        logFile.writeText("")
    }
}

private fun XrayCoreLogPaths.pathOf(logFile: XrayLogFile): String {
    return when (logFile) {
        XrayLogFile.Error -> errorLogPath
        XrayLogFile.Access -> accessLogPath
    }
}

internal enum class XrayLogFile {
    Error,
    Access,
}

private const val LogTag = "CoreLogClear"
