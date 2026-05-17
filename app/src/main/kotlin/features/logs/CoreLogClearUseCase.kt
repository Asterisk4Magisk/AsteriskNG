package features.logs

import android.content.Context
import app.modes.RunModeTproxy
import engine.tproxy.TproxyRootRunner
import engine.xray.XrayCoreLogPaths
import engine.xray.clearCoreLogFilesAsApp
import engine.xray.prepareXrayCoreLogPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import system.AndroidRootShellGateway

internal class CoreLogClearUseCase(
    context: Context,
    rootAccess: AndroidRootShellGateway,
) {
    private val appContext = context.applicationContext
    private val rootRunner = TproxyRootRunner(rootAccess)

    suspend fun clear(logFile: XrayLogFile, runMode: Int) {
        val logPath = appContext.prepareXrayCoreLogPaths().pathOf(logFile)
        if (logPath.isBlank()) {
            return
        }

        if (runMode == RunModeTproxy) {
            rootRunner.truncateCoreLogFiles(listOf(logPath))
        } else {
            withContext(Dispatchers.IO) {
                clearCoreLogFilesAsApp(
                    logPaths = listOf(logPath),
                    logTag = LogTag,
                )
            }
        }
    }

    private fun XrayCoreLogPaths.pathOf(logFile: XrayLogFile): String {
        return when (logFile) {
            XrayLogFile.Error -> errorLogPath
            XrayLogFile.Access -> accessLogPath
        }
    }

    private companion object {
        const val LogTag = "CoreLogClearUseCase"
    }
}

internal enum class XrayLogFile {
    Error,
    Access,
}
