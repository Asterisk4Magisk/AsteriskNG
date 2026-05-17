package engine.tproxy

import android.content.Context
import app.modes.RunModeTproxy
import app.R
import features.logs.AndroidAppLogger
import features.logs.CoreLogFileTailer
import engine.proxy.mode.AndroidModeProxyEngine
import engine.xray.clearCoreLogRepositories
import engine.xray.startCoreLogTailers
import system.AndroidRootShellGateway
import java.io.File
import engine.proxy.ProxyEngineStartRequest
import engine.proxy.ProxyEngineStatus

internal class TproxyEngine(
    private val context: Context,
    private val rootAccess: AndroidRootShellGateway,
) : AndroidModeProxyEngine {
    override val runMode: Int = RunModeTproxy
    private val rootRunner = TproxyRootRunner(rootAccess)
    private var startConfig: TproxyStartConfig? = null
    private var logFileTailers: List<CoreLogFileTailer> = emptyList()

    override suspend fun start(request: ProxyEngineStartRequest): ProxyEngineStatus {
        if (!rootAccess.hasRootAccess()) {
            error(context.getString(R.string.error_tproxy_root_required))
        }
        stop()
        val config = TproxyConfigFactory.create(context, request)
        if (!File(config.xrayCorePath).canExecute()) {
            File(config.xrayCorePath).setExecutable(true, false)
        }
        clearCoreLogRepositories()
        rootRunner.deleteCoreLogFiles(config.coreLogPaths)
        logFileTailers = config.coreLogPaths.startCoreLogTailers(config.enableAccessLog)
        runCatching {
            rootRunner.start(config)
            if (request.appState.enableTproxyBootScript) {
                rootRunner.installBootScript(config)
            } else {
                rootRunner.uninstallBootScript()
            }
        }.onFailure { error ->
            runCatching { rootRunner.stop(config) }
                .onFailure { stopError -> AndroidAppLogger.warn(LogTag, "Failed to clean up TPROXY after startup failure", stopError) }
            logFileTailers.forEach { tailer -> tailer.stop() }
            logFileTailers = emptyList()
            AndroidAppLogger.error(LogTag, "Failed to start TPROXY mode", error)
            throw IllegalStateException(
                context.getString(R.string.error_tproxy_start_failed, error.message.orEmpty()),
                error,
            )
        }
        startConfig = config
        return status()
    }

    override suspend fun stop(): ProxyEngineStatus {
        logFileTailers.forEach { tailer -> tailer.stop() }
        logFileTailers = emptyList()
        runCatching {
            rootRunner.stop(
                config = startConfig,
                fallbackRuntimeConfig = TproxyConfigFactory.runtimeConfig(context),
            )
        }.onFailure { error ->
            AndroidAppLogger.warn(LogTag, "Failed to stop TPROXY mode", error)
        }
        startConfig = null
        return status()
    }

    override suspend fun status(): ProxyEngineStatus {
        val runtimeConfig = startConfig?.toRuntimeConfig() ?: TproxyConfigFactory.runtimeConfig(context)
        val running = rootRunner.isRunning(
            pidPath = runtimeConfig.pidPath,
            xrayCorePath = runtimeConfig.xrayCorePath,
        )
        return ProxyEngineStatus(running = running, runMode = runMode)
    }

    private fun TproxyStartConfig.toRuntimeConfig(): TproxyRuntimeConfig {
        return TproxyRuntimeConfig(
            xrayCorePath = xrayCorePath,
            pidPath = pidPath,
        )
    }

    private companion object {
        private const val LogTag = "TproxyEngine"
    }
}
