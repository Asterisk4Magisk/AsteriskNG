// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root

import engine.xray.XrayCoreLogPaths
import features.logs.AndroidAppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import system.AndroidRootShellGateway
import system.ShellExecOptions
import kotlin.time.Duration.Companion.milliseconds

internal data class RootReadinessCheck(
    val description: String,
    val command: String,
    val failureMessage: String,
)

internal abstract class RootModeRunner<Config : RootModeStartConfig>(
    protected val rootAccess: AndroidRootShellGateway,
    private val modeName: String,
    private val runtimeConfigTag: String,
    private val logTag: String,
) {
    suspend fun start(config: Config) = withContext(Dispatchers.IO) {
        stop(config.root.runtimeLayout)
        writeRootConfigFile(config.root)
        config.rootEbpfConfig?.writeRuntimeFiles()
        prepareModeRuntimeFiles(config)
        runRootCommand(config.root.buildPrepareRuntimeCommand(), "Failed to prepare $modeName environment")
        runRootCommandIfNotBlank(
            command = config.root.buildStartIpv6DisablerCommand(),
            failureMessage = "Failed to start IPv6 disabler daemon",
        )
        runRootCommand(config.root.buildStartDaemonCommand(), "Failed to start Xray-core daemon")
        runRootCommandIfNotBlank(
            command = buildPostCoreStartCommand(config),
            failureMessage = "Failed to start $modeName helper runtime",
        )
        runRootCommandIfNotBlank(
            command = config.rootEbpfConfig?.buildStartCommand().orEmpty(),
            failureMessage = "Failed to start $modeName eBPF matcher",
        )

        val readinessCheck = buildReadinessCheck(config)
        if (!waitForRuntimeReady(readinessCheck)) {
            failStartup(config, readinessCheck.failureMessage)
        }
        if (!isRunning(config.root.runtimeLayout)) {
            failStartup(config, "Xray-core process exited or did not match the expected $modeName runtime state")
        }
        if (!isModeRuntimeRunning(config)) {
            failStartup(config, "$modeName helper runtime exited or did not match the expected runtime state")
        }

        runRootCommand(buildSetupRulesCommand(config), "Failed to install $modeName rules")
    }

    suspend fun stop(runtimeLayout: RootRuntimeLayout) = withContext(Dispatchers.IO) {
        val command = buildStopCommand(runtimeLayout)
        val result = rootAccess.exec(command, ShellExecOptions(logFailure = false))
        if (result.errno != 0) {
            AndroidAppLogger.warn(logTag, "Failed to stop $modeName cleanly:\n${result.stderr}")
        }
    }

    suspend fun installBootScript(config: Config) = withContext(Dispatchers.IO) {
        writeRootConfigFile(config.root)
        config.rootEbpfConfig?.writeRuntimeFiles()
        prepareModeRuntimeFiles(config)
        val command = config.buildInstallRootBootScriptCommand(
            modeName = modeName,
            buildSetupRulesCommand = ::buildSetupRulesCommand,
            buildPostCoreStartCommand = ::buildPostCoreStartCommand,
            buildReadinessCheck = ::buildReadinessCheck,
            bootReadinessCheckAttempts = BootReadinessCheckAttempts,
            appendStartupSummary = { targetConfig -> appendStartupSummary(targetConfig) },
            appendStartupFailureDiagnostics = { targetConfig -> appendStartupFailureDiagnostics(targetConfig) },
        )
        runRootCommand(command, "Failed to install $modeName boot script")
    }

    suspend fun uninstallBootScript(config: RootStartConfig) = withContext(Dispatchers.IO) {
        rootAccess.removeRootBootScript(
            runtimeLayout = config.runtimeLayout,
            coreLogPaths = config.coreLogPaths,
            failureMessage = "Failed to remove $modeName boot script",
        )
    }

    suspend fun prepareCoreLogFiles(coreLogPaths: XrayCoreLogPaths) = withContext(Dispatchers.IO) {
        val command = coreLogPaths.buildPrepareCoreLogFilesCommand()
        if (command.isBlank()) {
            return@withContext
        }
        runRootCommand(command, "Failed to prepare Xray log files")
    }

    suspend fun ownsRuntime(runtimeLayout: RootRuntimeLayout): Boolean = withContext(Dispatchers.IO) {
        rootAccess.exec(runtimeLayout.buildRootConfigTagMatchCommand(runtimeConfigTag), ShellExecOptions(logFailure = false)).errno == 0
    }

    open suspend fun isRunning(runtimeLayout: RootRuntimeLayout): Boolean = withContext(Dispatchers.IO) {
        val command = buildString {
            appendScript("${runtimeLayout.buildRootConfigTagMatchCommand(runtimeConfigTag)} || exit 1")
            append(
                buildRootProcessMatchCommand(
                    pidPath = runtimeLayout.pidPath,
                    executablePath = runtimeLayout.xrayCorePath,
                    uid = RootXrayUid,
                    gid = RootXrayGid,
                ),
            )
        }
        rootAccess.exec(command, ShellExecOptions(logFailure = false)).errno == 0
    }

    protected abstract fun buildSetupRulesCommand(config: Config): String

    protected abstract fun buildCleanupRulesCommand(): String

    protected open suspend fun prepareModeRuntimeFiles(config: Config) = Unit

    protected open fun buildPostCoreStartCommand(config: Config): String = ""

    protected open fun buildPostCoreStopCommand(runtimeLayout: RootRuntimeLayout): String = ""

    protected open suspend fun isModeRuntimeRunning(config: Config): Boolean = true

    protected abstract fun buildReadinessCheck(config: Config): RootReadinessCheck

    protected abstract suspend fun collectReadinessDiagnostics(config: Config): String

    protected abstract fun StringBuilder.appendStartupSummary(config: Config)

    protected abstract fun StringBuilder.appendStartupFailureDiagnostics(config: Config)

    protected suspend fun runRootCommand(command: String, failureMessage: String) {
        val result = rootAccess.exec(command, ShellExecOptions(logFailure = false))
        if (result.errno != 0) {
            error(
                buildRootDiagnosticMessage(
                    failureMessage,
                    result.stderr,
                    result.stdout,
                ),
            )
        }
    }

    protected suspend fun runRootCommandIfNotBlank(command: String, failureMessage: String) {
        if (command.isBlank()) {
            return
        }
        runRootCommand(command, failureMessage)
    }

    private suspend fun waitForRuntimeReady(readinessCheck: RootReadinessCheck): Boolean {
        val checkCommand = readinessCheck.command
        val deadline = System.currentTimeMillis() + RuntimeReadyTimeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val result = rootAccess.exec(checkCommand, ShellExecOptions(logFailure = false))
            if (result.errno == 0) {
                return true
            }
            delay(RuntimeReadyIntervalMillis.milliseconds)
        }
        val result = rootAccess.exec(checkCommand, ShellExecOptions(logFailure = false))
        return result.errno == 0
    }

    private suspend fun failStartup(config: Config, message: String): Nothing {
        val errorLog = config.root.collectRootErrorLogTail(rootAccess)
        val processDiagnostics = config.root.collectRootProcessDiagnostics(rootAccess)
        val modeDiagnostics = collectReadinessDiagnostics(config)
        runRootCommand(buildStopCommand(config.root.runtimeLayout), "Failed to clean up after Xray-core startup failure")
        error(
            buildRootDiagnosticMessage(
                message,
                processDiagnostics,
                modeDiagnostics,
                errorLog,
            ),
        )
    }

    private fun buildStopCommand(runtimeLayout: RootRuntimeLayout): String {
        return buildRootStopCommand(
            runtimeLayout = runtimeLayout,
            uid = RootXrayUid,
            gid = RootXrayGid,
            cleanupRulesCommand = buildCleanupRulesCommand() + buildPostCoreStopCommand(runtimeLayout),
        )
    }

    protected companion object {
        const val BootReadinessCheckAttempts = 5

        private const val RuntimeReadyTimeoutMillis = 5_000L
        private const val RuntimeReadyIntervalMillis = 100L
    }
}
