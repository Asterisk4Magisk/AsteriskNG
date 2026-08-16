// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root

import android.content.Context
import engine.proxy.LocalProxyRuntime
import engine.proxy.ProxyEngineStartRequest
import engine.proxy.ProxyEngineStatus
import engine.proxy.mode.AndroidModeProxyEngine
import engine.root.config.prepareRootConfigBuildContext
import engine.root.config.RootModeStartConfig
import engine.root.config.DefaultRootHttpProxyPort as ConfigDefaultRootHttpProxyPort
import engine.root.config.RootBpf2SocksDefaultBridgePort as ConfigRootBpf2SocksDefaultBridgePort
import engine.root.mode.RootModeCatalog
import engine.root.mode.RootModeDefinition
import engine.root.mode.DefaultTproxyPort as ModeDefaultTproxyPort
import engine.root.mode.DefaultTun2SocksProxyPort as ModeDefaultTun2SocksProxyPort
import engine.root.runtime.RootRuntimeBusyException
import engine.root.runtime.RootRuntimeConflictException
import engine.root.runtime.RootSupervisorController
import engine.root.runtime.toStableProxyEngineStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlin.coroutines.cancellation.CancellationException
import system.RootShellGateway

internal class RootModeEngine(
    private val context: Context,
    private val rootAccess: RootShellGateway,
    private val definition: RootModeDefinition,
) : AndroidModeProxyEngine {
    private val controller = RootSupervisorController(context, rootAccess)

    internal val daemonMode: engine.root.daemon.config.AsteriskdMode
        get() = definition.daemonMode

    override val runMode: Int
        get() = definition.runMode

    override suspend fun start(request: ProxyEngineStartRequest): ProxyEngineStatus {
        return start(request, explicitRestart = false, resumeAlreadyChecked = false)
    }

    suspend fun startAfterResumeCheck(request: ProxyEngineStartRequest): ProxyEngineStatus {
        return start(request, explicitRestart = false, resumeAlreadyChecked = true)
    }

    suspend fun restart(request: ProxyEngineStartRequest): ProxyEngineStatus {
        return start(request, explicitRestart = true, resumeAlreadyChecked = false)
    }

    suspend fun resumeIfRunning(request: ProxyEngineStartRequest): ProxyEngineStatus? {
        if (!rootAccess.hasRootAccess()) error(context.getString(definition.rootRequiredErrorResId))
        controller.preflightStart(definition.daemonMode, explicitRestart = false) ?: return null
        val restored = buildLocalProxyOptions(request)
        val confirmed = controller.preflightStart(definition.daemonMode, explicitRestart = false) ?: return null
        LocalProxyRuntime.update(restored)
        return controller.proxyStatus(confirmed, runMode, definition.daemonMode)
    }

    private suspend fun start(
        request: ProxyEngineStartRequest,
        explicitRestart: Boolean,
        resumeAlreadyChecked: Boolean,
    ): ProxyEngineStatus {
        if (!resumeAlreadyChecked && !rootAccess.hasRootAccess()) {
            error(context.getString(definition.rootRequiredErrorResId))
        }
        if (!explicitRestart) {
            if (!resumeAlreadyChecked) resumeIfRunning(request)?.let { return it }
        } else {
            controller.preflightStart(definition.daemonMode, explicitRestart = true)
        }

        LocalProxyRuntime.clear()
        val rootContext = context.prepareRootConfigBuildContext(request)
        val config = definition.buildConfig(rootContext)
        require(config.asteriskdConfig.mode == definition.daemonMode)
        return runCatching {
            val snapshot = if (explicitRestart) {
                controller.restart(config.root, config.asteriskdConfig)
            } else {
                controller.start(config.root, config.asteriskdConfig)
            }
            controller.requireRunning(snapshot, definition.daemonMode)
            LocalProxyRuntime.update(config.localProxyOptions)
            controller.proxyStatus(snapshot, runMode, definition.daemonMode)
        }.onFailure {
            LocalProxyRuntime.clear()
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            if (error is RootRuntimeConflictException || error is RootRuntimeBusyException) throw error
            throw IllegalStateException(
                context.getString(definition.startFailedErrorResId, error.message.orEmpty()),
                error,
            )
        }
    }

    private fun buildLocalProxyOptions(request: ProxyEngineStartRequest) =
        LocalProxyRuntime.current() ?: definition
            .buildConfig(context.prepareRootConfigBuildContext(request))
            .localProxyOptions

    override suspend fun stop(): ProxyEngineStatus {
        controller.stopOwn()
        LocalProxyRuntime.clear()
        return status()
    }

    suspend fun shutdown(): ProxyEngineStatus {
        controller.shutdownOwn()
        LocalProxyRuntime.clear()
        return status()
    }

    suspend fun reconfigureServiceControl(request: ProxyEngineStartRequest): Boolean {
        if (!rootAccess.hasRootAccess()) error(context.getString(definition.rootRequiredErrorResId))
        val config = definition.buildConfig(context.prepareRootConfigBuildContext(request))
        require(config.asteriskdConfig.mode == definition.daemonMode)
        val wasRunning = controller.reconfigureServiceControl(config.root, config.asteriskdConfig)
        if (wasRunning) {
            LocalProxyRuntime.update(config.localProxyOptions)
        } else {
            LocalProxyRuntime.clear()
        }
        return wasRunning
    }

    suspend fun disableServiceControlWithoutConfig() {
        if (!rootAccess.hasRootAccess()) error(context.getString(definition.rootRequiredErrorResId))
        controller.disableServiceControlWithoutConfig()
        LocalProxyRuntime.clear()
    }

    suspend fun ownsRuntime(): Boolean {
        return controller.ownsRuntime()
    }

    override suspend fun status(): ProxyEngineStatus {
        return controller.proxyStatus(runMode, definition.daemonMode)
    }

    fun observeStatus(): Flow<ProxyEngineStatus> = controller.observeStatus()
        .mapNotNull { snapshot ->
            snapshot.toStableProxyEngineStatus(runMode, definition.daemonMode)
        }
        .distinctUntilChanged()

    companion object {
        const val DefaultTproxyPort = ModeDefaultTproxyPort
        const val DefaultTun2SocksProxyPort = ModeDefaultTun2SocksProxyPort
        const val DefaultBpf2SocksBridgePort = ConfigRootBpf2SocksDefaultBridgePort
        const val DefaultHttpProxyPort = ConfigDefaultRootHttpProxyPort

        fun createAll(context: Context, rootAccess: RootShellGateway): List<RootModeEngine> =
            RootModeCatalog.definitions.map { definition -> RootModeEngine(context, rootAccess, definition) }

        fun prepareConfig(context: Context, runMode: Int, request: ProxyEngineStartRequest): RootModeStartConfig {
            val definition = RootModeCatalog.require(runMode)
            return definition.buildConfig(context.prepareRootConfigBuildContext(request)).also { config ->
                require(config.asteriskdConfig.mode == definition.daemonMode)
            }
        }

        fun generateCoreConfig(context: Context, runMode: Int, request: ProxyEngineStartRequest): String =
            prepareConfig(context, runMode, request).root.xrayConfigJson
    }
}
