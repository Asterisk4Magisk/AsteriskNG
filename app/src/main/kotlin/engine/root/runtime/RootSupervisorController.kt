// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import android.content.Context
import android.os.Build
import engine.proxy.ProxyEngineStatus
import engine.root.config.RootStartConfig
import engine.root.daemon.AsteriskdClient
import engine.root.daemon.config.AsteriskdConfig
import engine.root.daemon.config.AsteriskdConfigEncoder
import engine.root.daemon.config.AsteriskdMode
import engine.root.daemon.config.AsteriskdOwner
import engine.root.daemon.control.AsteriskdControlCodec
import engine.root.daemon.control.AsteriskdControlResponse
import engine.root.daemon.control.AsteriskdPhase
import engine.root.daemon.control.AsteriskdResultCode
import engine.root.daemon.control.AsteriskdSnapshot
import engine.root.publication.RootBootPublicationCommand
import engine.root.publication.RootPublicationBundle
import engine.root.publication.RootPublicationCommand
import engine.root.publication.RootPublicationLaunchMode
import engine.root.publication.RootPublicationStager
import engine.root.publication.prepareRootPublicationDirectories
import engine.root.publication.rootRuntimeLayout
import engine.root.publication.validateElfHeaderFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import system.RootShellGateway
import system.ShellExecOptions
import system.ShellExecResult
import kotlin.time.Duration.Companion.milliseconds

internal class RootSupervisorController(
    context: Context,
    private val shell: RootShellGateway,
) {
    private val appContext = context.applicationContext
    private val runtimeLayout = appContext.rootRuntimeLayout()
    private val client = AsteriskdClient(shell)
    suspend fun status(): AsteriskdControlResponse = client.status(runtimeLayout.asteriskdPath)

    fun observeStatus(): Flow<AsteriskdSnapshot> = client.observeStatus(runtimeLayout.asteriskdPath)

    suspend fun preflightStart(expectedMode: AsteriskdMode, explicitRestart: Boolean): AsteriskdSnapshot? {
        return status().preflightStart(AsteriskdOwner.AsteriskNg, expectedMode, explicitRestart)
    }

    suspend fun ownsRuntime(): Boolean = status().boundSnapshot()?.owner == AsteriskdOwner.AsteriskNg

    suspend fun proxyStatus(runMode: Int, expectedMode: AsteriskdMode): ProxyEngineStatus {
        val snapshot = status().boundSnapshot() ?: return ProxyEngineStatus(running = false, runMode = runMode)
        return snapshot.toProxyEngineStatus(runMode, expectedMode)
    }

    fun proxyStatus(snapshot: AsteriskdSnapshot, runMode: Int, expectedMode: AsteriskdMode): ProxyEngineStatus =
        snapshot.toProxyEngineStatus(runMode, expectedMode)

    fun requireRunning(snapshot: AsteriskdSnapshot, expectedMode: AsteriskdMode) {
        snapshot.requireRunning(AsteriskdOwner.AsteriskNg, expectedMode)
    }

    suspend fun canPublishBoot(deferIfRuntimeBound: Boolean): Boolean {
        return status().canPublishBoot(AsteriskdOwner.AsteriskNg, deferIfRuntimeBound)
    }

    suspend fun requireUnbound() {
        status().boundSnapshot()?.let(::rejectBoundSnapshot)
    }

    suspend fun isUnbound(): Boolean = status().boundSnapshot() == null

    fun rejectBoundSnapshot(snapshot: AsteriskdSnapshot): Nothing {
        snapshot.rejectBound(AsteriskdOwner.AsteriskNg)
    }

    suspend fun start(
        root: RootStartConfig,
        config: AsteriskdConfig,
    ): AsteriskdSnapshot {
        status().boundSnapshot()?.let { snapshot ->
            val disposition = snapshot.ordinaryStartDisposition(AsteriskdOwner.AsteriskNg, config.mode)
            if (disposition == RootOrdinaryStartDisposition.Reuse) return snapshot
            if (disposition.shutdownBeforeLaunch) {
                shutdownOwn()
            }
            return launch(
                root = root,
                config = config,
                restartExpectedOwner = snapshot.owner,
                launchMode = RootPublicationLaunchMode.Service,
            )
        }

        return launch(root, config, restartExpectedOwner = null, RootPublicationLaunchMode.Service)
    }

    suspend fun restart(
        root: RootStartConfig,
        config: AsteriskdConfig,
    ): AsteriskdSnapshot {
        val snapshot = status().boundSnapshot()
        if (snapshot != null && snapshot.owner != AsteriskdOwner.AsteriskNg) {
            throw RootRuntimeConflictException(snapshot)
        }
        return launch(
            root = root,
            config = config,
            restartExpectedOwner = snapshot?.owner,
            launchMode = RootPublicationLaunchMode.Service,
        )
    }

    suspend fun reconfigureServiceControl(
        root: RootStartConfig,
        config: AsteriskdConfig,
    ): Boolean {
        val snapshot = status().boundSnapshot()
        if (snapshot != null && snapshot.owner != AsteriskdOwner.AsteriskNg) {
            throw RootRuntimeConflictException(snapshot)
        }
        val plan = try {
            serviceControlReconfigurePlan(snapshot?.phase, config.serviceControl.enabled)
        } catch (_: IllegalArgumentException) {
            throw RootRuntimeBusyException(requireNotNull(snapshot))
        }
        if (plan.shutdownRequired) shutdownOwn()
        when (plan.launchMode) {
            RootPublicationLaunchMode.Service -> launch(
                root,
                config,
                restartExpectedOwner = snapshot?.owner,
                launchMode = RootPublicationLaunchMode.Service,
            )
            RootPublicationLaunchMode.Monitor -> launch(
                root,
                config,
                restartExpectedOwner = snapshot?.owner,
                launchMode = RootPublicationLaunchMode.Monitor,
            )
            RootPublicationLaunchMode.None -> Unit
        }
        return plan.launchMode == RootPublicationLaunchMode.Service
    }

    suspend fun disableServiceControlWithoutConfig() {
        val snapshot = status().boundSnapshot() ?: return
        if (snapshot.owner != AsteriskdOwner.AsteriskNg) {
            throw RootRuntimeConflictException(snapshot)
        }
        if (snapshot.phase != AsteriskdPhase.Stopped) {
            throw RootRuntimeBusyException(snapshot)
        }
        shutdownOwn()
    }

    private suspend fun launch(
        root: RootStartConfig,
        config: AsteriskdConfig,
        restartExpectedOwner: AsteriskdOwner?,
        launchMode: RootPublicationLaunchMode,
    ): AsteriskdSnapshot {
        preparePublication(config)
        val staged = RootPublicationStager.stage(
            root.publicationStagingDirectory,
            root.xrayConfigJson,
            AsteriskdConfigEncoder.encode(config),
        )
        staged.use { staged ->
            val publication = RootPublicationCommand.build(
                RootPublicationBundle(
                    runtimeLayout = runtimeLayout,
                    coreConfigSourcePath = staged.coreConfig.absolutePath,
                    asteriskdConfigSourcePath = staged.asteriskdConfig.absolutePath,
                    bootEnabled = root.enableBoot,
                    launchMode = launchMode,
                    restartExpectedOwner = restartExpectedOwner?.wireValue,
                ),
            )
            val launchResult = shell.exec(publication, ShellExecOptions(logFailure = false))
            if (launchResult.errno != 0 || launchResult.stdout.isNotBlank()) {
                throw launchFailure(launchResult)
            }
            val snapshot = withTimeoutOrNull(StartTimeoutMilliseconds.milliseconds) {
                when (launchMode) {
                    RootPublicationLaunchMode.Service -> client.awaitRunning(runtimeLayout.asteriskdPath)
                    RootPublicationLaunchMode.Monitor -> client.awaitStopped(runtimeLayout.asteriskdPath)
                    RootPublicationLaunchMode.None -> error("A non-launch publication has no runtime snapshot")
                }
            } ?: throw IllegalStateException("asteriskd did not reach the requested phase before timeout")
            if (snapshot.owner != AsteriskdOwner.AsteriskNg) throw RootRuntimeConflictException(snapshot)
            require(snapshot.mode == config.mode) { "Unexpected ROOT mode ${snapshot.mode.wireValue}" }
            return snapshot
        }
    }

    suspend fun stopOwn(): AsteriskdControlResponse {
        val initial = status()
        val initialSnapshot = initial.boundSnapshot() ?: return initial
        if (initialSnapshot.owner != AsteriskdOwner.AsteriskNg) {
            throw RootRuntimeConflictException(initialSnapshot)
        }
        val result = shell.exec(RootStopOwnCommand.build(runtimeLayout), ShellExecOptions(logFailure = false))
        val response = AsteriskdControlCodec.decodeShellResponse(result)
        when (response.requestId) {
            "status" -> response.boundSnapshot()?.let { snapshot ->
                if (snapshot.owner != AsteriskdOwner.AsteriskNg) throw RootRuntimeConflictException(snapshot)
            }
            "stop" -> Unit
            else -> error("Unexpected stop-own response id")
        }
        if (response.result.code == AsteriskdResultCode.Ok || response.result.code == AsteriskdResultCode.NotRunning) {
            return response
        }
        error(response.result.message ?: "Failed to stop asteriskd")
    }

    suspend fun shutdownOwn(): AsteriskdControlResponse {
        val initial = status()
        val initialSnapshot = initial.boundSnapshot() ?: return initial
        if (initialSnapshot.owner != AsteriskdOwner.AsteriskNg) {
            throw RootRuntimeConflictException(initialSnapshot)
        }
        val result = shell.exec(
            RootShutdownOwnCommand.build(runtimeLayout),
            ShellExecOptions(logFailure = false),
        )
        val response = AsteriskdControlCodec.decodeShellResponse(result)
        when (response.requestId) {
            "status" -> response.boundSnapshot()?.let { snapshot ->
                if (snapshot.owner != AsteriskdOwner.AsteriskNg) {
                    throw RootRuntimeConflictException(snapshot)
                }
            }
            "shutdown", "stop" -> Unit
            else -> error("Unexpected shutdown-own response id")
        }
        if (response.result.code == AsteriskdResultCode.Ok ||
            response.result.code == AsteriskdResultCode.NotRunning
        ) {
            return response
        }
        error(response.result.message ?: "Failed to shutdown asteriskd")
    }

    suspend fun publishBoot(
        root: RootStartConfig,
        config: AsteriskdConfig,
    ) {
        status().boundSnapshot()?.let(::rejectBoundSnapshot)
        preparePublication(config)
        val staged = RootPublicationStager.stage(
            root.publicationStagingDirectory,
            root.xrayConfigJson,
            AsteriskdConfigEncoder.encode(config),
        )
        staged.use { staged ->
            val result = shell.exec(
                RootPublicationCommand.build(
                    RootPublicationBundle(
                        runtimeLayout = runtimeLayout,
                        coreConfigSourcePath = staged.coreConfig.absolutePath,
                        asteriskdConfigSourcePath = staged.asteriskdConfig.absolutePath,
                        bootEnabled = true,
                        launchMode = RootPublicationLaunchMode.None,
                    ),
                ),
                ShellExecOptions(logFailure = false),
            )
            requirePublicationSuccess(result)
        }
    }

    private fun requirePublicationSuccess(result: ShellExecResult) {
        if (result.errno != 0 || result.stdout.isNotBlank()) throw launchFailure(result)
    }

    suspend fun removeBoot() {
        val initial = status()
        initial.boundSnapshot()?.let { snapshot ->
            if (snapshot.owner != AsteriskdOwner.AsteriskNg) throw RootRuntimeConflictException(snapshot)
            throw RootRuntimeBusyException(snapshot)
        }
        val result = shell.exec(
            RootBootPublicationCommand.buildRemoval(runtimeLayout),
            ShellExecOptions(logFailure = false),
        )
        requirePublicationSuccess(result)
    }

    private fun launchFailure(result: ShellExecResult): IllegalStateException {
        val controlResponse = result.controlResponseOrNull()
        controlResponse?.result?.snapshot?.rejectBound(AsteriskdOwner.AsteriskNg)
        val message = controlResponse?.result?.message ?: sanitizeLauncherStderr(result.stderr)
            .ifBlank { "asteriskd launcher exited with ${result.errno}" }
        return IllegalStateException(message)
    }

    private fun preparePublication(config: AsteriskdConfig) {
        appContext.prepareRootPublicationDirectories()
        validateElfHeaderFile(config.coreExecutablePath, Build.SUPPORTED_ABIS.toList())
    }

}

internal fun sanitizeLauncherStderr(stderr: String): String {
    val retained = mutableListOf<String>()
    var readingFileContexts = false
    stderr.lineSequence().forEach { line ->
        if (line.trim() == "SELinux: Loaded file context from:") {
            readingFileContexts = true
            return@forEach
        }
        val trimmed = line.trim()
        if (
            readingFileContexts &&
            trimmed.startsWith('/') &&
            "/selinux/" in trimmed &&
            trimmed.endsWith("_file_contexts")
        ) {
            return@forEach
        }
        readingFileContexts = false
        retained += line
    }
    return retained.joinToString("\n").trim().ifBlank { stderr.trim() }
}

private const val StartTimeoutMilliseconds = 15_000L
