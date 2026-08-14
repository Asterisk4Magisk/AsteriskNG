// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.proxy

import android.content.Context
import android.content.Intent
import app.AppState
import app.ProxyServerState
import app.modes.RunModeVpnService
import engine.stats.ProxyTrafficStatsRuntime
import engine.stats.ProxyTrafficStatsRuntimeStore
import engine.stats.ProxyTrafficStatsService
import engine.stats.XrayStatsApiListenAddress
import engine.stats.resolveXrayStatsApiPort
import engine.stats.xrayStatsApiExcludedPorts
import engine.proxy.mode.AndroidModeProxyEngine
import engine.root.RootModeEngine
import engine.root.runtime.model.RootRuntimeMode
import engine.root.runtime.model.RootRuntimeOwner
import engine.vpn.VpnXrayEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import system.AndroidRootShellGateway

class AndroidProxyEngine(
    context: Context,
    rootAccess: AndroidRootShellGateway,
    requestVpnPermission: suspend (Intent) -> Boolean,
) {
    private val appContext = context.applicationContext
    private val vpnXrayEngine = VpnXrayEngine(appContext, requestVpnPermission)
    private val rootEngines = RootModeEngine.createAll(appContext, rootAccess)
    private val rootEnginesByRunMode = rootEngines.associateBy(RootModeEngine::runMode)
    private val operationMutex = Mutex()
    private var activeEngine: AndroidModeProxyEngine? = null

    suspend fun start(request: ProxyEngineStartRequest): ProxyEngineStatus = operationMutex.withLock {
        startUnlocked(request)
    }

    suspend fun stop(preferredRunMode: Int? = null): ProxyEngineStatus = operationMutex.withLock {
        stopUnlocked(preferredRunMode)
    }

    suspend fun stopCurrentRunMode(runMode: Int): ProxyEngineStatus = operationMutex.withLock {
        stopRunModeUnlocked(runMode)
    }

    suspend fun restart(request: ProxyEngineStartRequest): ProxyEngineStatus = operationMutex.withLock {
        startUnlocked(request, explicitRestart = true)
    }

    suspend fun status(
        preferredRunMode: Int? = null,
        appState: AppState? = null,
    ): ProxyEngineStatus = operationMutex.withLock {
        statusUnlocked(preferredRunMode, appState)
    }

    private suspend fun startUnlocked(
        request: ProxyEngineStartRequest,
        explicitRestart: Boolean = false,
    ): ProxyEngineStatus = withContext(Dispatchers.Default) {
        ProxyTrafficStatsService.reconcile(appContext, null)
        val requestedEngine = request.appState.runMode.engine()
        var rootResumeChecked = false
        if (shouldResumeRootBeforeResolvingPorts(explicitRestart, activeEngine != null, requestedEngine is RootModeEngine)) {
            requestedEngine as RootModeEngine
            requestedEngine.resumeIfRunning(request)?.let { status ->
                activeEngine = requestedEngine
                return@withContext status.copy(appState = request.appState)
            }
            rootResumeChecked = true
        }
        val resolvedBaseRequest = request.copy(appState = request.appState.withResolvedDynamicLocalProxyPort())
        val (resolvedRequest, trafficStatsRuntime) = resolvedBaseRequest.withTrafficStatsConfig()
        val nextEngine = resolvedRequest.appState.runMode.engine()
        val currentEngine = activeEngine ?: findEngineToStop(resolvedRequest.appState.runMode)
        val rootToRootRestart = explicitRestart && currentEngine is RootModeEngine && nextEngine is RootModeEngine
        if (currentEngine != null && currentEngine !== nextEngine && !rootToRootRestart) {
            currentEngine.stop()
        }
        activeEngine = nextEngine
        runCatching {
            when {
                explicitRestart && nextEngine is RootModeEngine -> nextEngine.restart(resolvedRequest)
                shouldUsePreResolvedRootStart(
                    explicitRestart = explicitRestart,
                    resumeChecked = rootResumeChecked,
                    nextEngineIsRoot = nextEngine is RootModeEngine,
                ) -> (nextEngine as RootModeEngine).startAfterResumeCheck(resolvedRequest)
                else -> nextEngine.start(resolvedRequest)
            }
                .copy(appState = resolvedRequest.appState)
        }.onSuccess { status ->
            val runtime = if (status.running) trafficStatsRuntime else null
            ProxyTrafficStatsService.reconcile(appContext, runtime)
        }.onFailure {
            ProxyTrafficStatsService.reconcile(appContext, null)
        }.getOrThrow()
    }

    private suspend fun stopUnlocked(preferredRunMode: Int? = null): ProxyEngineStatus = withContext(Dispatchers.Default) {
        ProxyTrafficStatsService.reconcile(appContext, null)
        val engine = findEngineToStop(preferredRunMode)
        val stoppedMode = engine?.runMode
        engine?.stop()
        activeEngine = null
        ProxyEngineStatus(running = false, runMode = stoppedMode)
    }

    private suspend fun stopRunModeUnlocked(runMode: Int): ProxyEngineStatus = withContext(Dispatchers.Default) {
        ProxyTrafficStatsService.reconcile(appContext, null)
        val engine = runMode.engine()
        activeEngine
            ?.takeIf { active -> active !== engine }
            ?.stop()
        val status = engine.stop()
        activeEngine = null
        status
    }

    private suspend fun findEngineToStop(preferredRunMode: Int?): AndroidModeProxyEngine? {
        val preferredEngine = preferredRunMode?.engine()
        return activeEngine
            ?: preferredEngine?.takeIf { it.status().running }
            ?: preferredEngine?.takeIf { it.ownsRootRuntime() }
            ?: rootEngines.firstOrNull { engine -> engine.status().running }
            ?: vpnXrayEngine.takeIf { it.status().running }
            ?: rootEngines.firstOrNull { engine -> engine.ownsRuntime() }
    }

    private suspend fun statusUnlocked(
        preferredRunMode: Int? = null,
        appState: AppState? = null,
    ): ProxyEngineStatus = withContext(Dispatchers.Default) {
        var rootStatus: ProxyEngineStatus? = null

        suspend fun probeRoot(engine: RootModeEngine): ProxyEngineStatus {
            rootStatus?.let { return it }
            return normalizeRootRuntimeStatus(engine.status(), ::rootRunMode).also { rootStatus = it }
        }

        fun accept(status: ProxyEngineStatus, source: AndroidModeProxyEngine): ProxyEngineStatus {
            activeEngine = status.runMode?.let(rootEnginesByRunMode::get) ?: source
            return status.withTrafficStatsReconciled(appState)
        }

        val active = activeEngine
        val activeStatus = when (active) {
            is RootModeEngine -> probeRoot(active)
            else -> active?.status()
        }
        if (activeStatus?.running == true) {
            return@withContext accept(activeStatus, checkNotNull(active))
        }

        var fallbackStatus = activeStatus
        preferredRunMode?.engine()?.let { preferredEngine ->
            val preferredStatus = if (preferredEngine is RootModeEngine) {
                probeRoot(preferredEngine)
            } else {
                preferredEngine.status()
            }
            if (preferredStatus.running) {
                return@withContext accept(preferredStatus, preferredEngine)
            }
            if (preferredStatus.rootSnapshot != null || fallbackStatus?.rootSnapshot == null) {
                fallbackStatus = preferredStatus
            }
        }

        if (rootStatus == null) {
            val probeEngine = rootEngines.first()
            val status = probeRoot(probeEngine)
            if (status.running) return@withContext accept(status, probeEngine)
            if (status.rootSnapshot != null && fallbackStatus?.rootSnapshot == null) {
                fallbackStatus = status
            }
        }

        if (active !== vpnXrayEngine && preferredRunMode != RunModeVpnService) {
            val status = vpnXrayEngine.status()
            if (status.running) return@withContext accept(status, vpnXrayEngine)
        }

        activeEngine = null
        (fallbackStatus ?: ProxyEngineStatus(running = false, runMode = preferredRunMode))
            .withTrafficStatsReconciled(appState)
    }

    private fun rootRunMode(mode: RootRuntimeMode): Int? = rootEngines
        .firstOrNull { engine -> engine.daemonMode.wireValue == mode.wireValue }
        ?.runMode

    private fun Int.engine(): AndroidModeProxyEngine {
        return rootEnginesByRunMode[this] ?: vpnXrayEngine
    }

    private suspend fun AndroidModeProxyEngine.ownsRootRuntime(): Boolean {
        return this is RootModeEngine && ownsRuntime()
    }

    private fun ProxyEngineStartRequest.withTrafficStatsConfig(): Pair<ProxyEngineStartRequest, ProxyTrafficStatsRuntime?> {
        if (!appState.enableTrafficStatsNotification || appState.runMode != RunModeVpnService) {
            return this to null
        }
        val port = resolveXrayStatsApiPort(
            preferredPort = ProxyTrafficStatsRuntimeStore.readPort(appContext),
            excludedPorts = appState.xrayStatsApiExcludedPorts(),
        )
        val request = copy(
            xrayStatsApiListenAddress = XrayStatsApiListenAddress,
            xrayStatsApiPort = port,
        )
        val statsApiConfig = checkNotNull(request.xrayStatsApiConfig())
        val runtime = ProxyTrafficStatsRuntime(
            listenAddress = statsApiConfig.listenAddress,
            port = statsApiConfig.port,
            serverName = selectedServer.trafficStatsServerName(),
            apiTag = statsApiConfig.apiTag,
        )
        return request to runtime
    }

    private fun ProxyEngineStatus.withTrafficStatsReconciled(appState: AppState?): ProxyEngineStatus {
        if (!running) {
            ProxyTrafficStatsService.reconcile(appContext, null)
            return this
        }
        val activeRunMode = runMode ?: appState?.runMode
        if (activeRunMode != RunModeVpnService) {
            ProxyTrafficStatsService.reconcile(appContext, null)
            return this
        }
        if (appState == null) {
            return this
        }
        if (!appState.enableTrafficStatsNotification) {
            ProxyTrafficStatsService.reconcile(appContext, null)
            return this
        }
        ProxyTrafficStatsRuntimeStore.read(appContext)?.let { runtime ->
            ProxyTrafficStatsService.reconcile(appContext, runtime)
        }
        return this
    }
}

internal fun normalizeRootRuntimeStatus(
    probed: ProxyEngineStatus,
    runModeFor: (RootRuntimeMode) -> Int?,
): ProxyEngineStatus {
    val snapshot = probed.rootSnapshot ?: return probed
    val activeRunMode = runModeFor(snapshot.mode) ?: probed.runMode ?: return probed
    return ProxyEngineStatus.fromRootSnapshot(
        localOwner = RootRuntimeOwner.AsteriskNg,
        runMode = activeRunMode,
        snapshot = snapshot,
    )
}

internal fun shouldResumeRootBeforeResolvingPorts(
    explicitRestart: Boolean,
    hasActiveEngine: Boolean,
    requestedIsRoot: Boolean,
): Boolean = !explicitRestart && !hasActiveEngine && requestedIsRoot

internal fun shouldUsePreResolvedRootStart(
    explicitRestart: Boolean,
    resumeChecked: Boolean,
    nextEngineIsRoot: Boolean,
): Boolean = !explicitRestart && resumeChecked && nextEngineIsRoot

private fun ProxyServerState.trafficStatsServerName(): String {
    val info = server.getInfo()
    return info.remarks.ifBlank { info.protocol }
}
