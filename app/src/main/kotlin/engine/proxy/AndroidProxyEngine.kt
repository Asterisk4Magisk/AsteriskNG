// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.proxy

import android.content.Context
import app.AppState
import app.R
import app.displayTitle
import app.modes.RunModeVpnService
import engine.proxy.mode.AndroidModeProxyEngine
import engine.root.RootModeEngine
import engine.root.runtime.model.RootRuntimeMode
import engine.root.runtime.model.RootRuntimeOwner
import engine.stats.ProxyTrafficStatsRuntime
import engine.stats.ProxyTrafficStatsRuntimeStore
import engine.stats.ProxyTrafficStatsService
import engine.stats.XrayStatsApiListenAddress
import engine.stats.resolveXrayStatsApiPort
import engine.stats.xrayStatsApiExcludedPorts
import engine.vpn.VpnXrayEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import system.AndroidRootShellGateway

/**
 * Process-wide proxy engine shared by the UI, quick settings tile and broadcast control so
 * that every lifecycle operation is serialized by one mutex. VPN permission handling is
 * caller specific and is therefore supplied per start or restart request.
 */
class AndroidProxyEngine(
    context: Context,
    rootAccess: AndroidRootShellGateway,
) {
    private val appContext = context.applicationContext
    private var requestVpnPermission: VpnPermissionRequester = DenyVpnPermission
    private val vpnXrayEngine = VpnXrayEngine(
        context = appContext,
        requestVpnPermission = { intent -> requestVpnPermission(intent) },
    )
    private val rootEngines = RootModeEngine.createAll(appContext, rootAccess)
    private val rootEnginesByRunMode = rootEngines.associateBy(RootModeEngine::runMode)
    private val operationMutex = Mutex()
    private val mutableRootStatusWatchGeneration = MutableStateFlow(0L)
    private var activeEngine: AndroidModeProxyEngine? = null
    internal val rootStatusWatchGeneration: StateFlow<Long> =
        mutableRootStatusWatchGeneration.asStateFlow()

    suspend fun start(
        request: ProxyEngineStartRequest,
        requestVpnPermission: VpnPermissionRequester = DenyVpnPermission,
    ): ProxyEngineStatus = operationMutex.withLock {
        withVpnPermissionRequester(requestVpnPermission) {
            startUnlocked(request)
        }
    }

    suspend fun stop(preferredRunMode: Int? = null): ProxyEngineStatus = operationMutex.withLock {
        stopUnlocked(preferredRunMode)
    }

    suspend fun stopCurrentRunMode(runMode: Int): ProxyEngineStatus = operationMutex.withLock {
        stopRunModeUnlocked(runMode)
    }

    suspend fun shutdownCurrentRunMode(runMode: Int): ProxyEngineStatus = operationMutex.withLock {
        shutdownRunModeUnlocked(runMode)
    }

    suspend fun <T> changeRootCore(
        runMode: Int,
        onRootStopped: () -> Unit,
        action: suspend () -> T,
    ): T = operationMutex.withLock {
        // A resident supervisor also retains the old executable path. Republish on next start.
        if (runMode in rootEnginesByRunMode) {
            shutdownRunModeUnlocked(runMode)
            onRootStopped()
        }
        action()
    }

    suspend fun restart(
        request: ProxyEngineStartRequest,
        requestVpnPermission: VpnPermissionRequester = DenyVpnPermission,
    ): ProxyEngineStatus {
        val status = operationMutex.withLock {
            withVpnPermissionRequester(requestVpnPermission) {
                startUnlocked(request, explicitRestart = true)
            }
        }
        mutableRootStatusWatchGeneration.update { generation -> generation + 1L }
        return status
    }

    private suspend fun <T> withVpnPermissionRequester(
        requester: VpnPermissionRequester,
        block: suspend () -> T,
    ): T {
        requestVpnPermission = requester
        return try {
            block()
        } finally {
            requestVpnPermission = DenyVpnPermission
        }
    }

    suspend fun reconfigureServiceControl(nextState: AppState): AppState {
        val appliedState = operationMutex.withLock {
            val rootEngine = rootEnginesByRunMode[nextState.runMode] ?: return@withLock nextState
            val resolvedState = nextState.withResolvedDynamicLocalProxyPort()
            val selectedServer = resolvedState.proxyServers.firstOrNull { server ->
                server.id == resolvedState.selectedProxyServerId
            }
            if (selectedServer == null) {
                check(!resolvedState.serviceControl.enabled) {
                    appContext.getString(R.string.settings_service_control_proxy_server_required)
                }
                withContext(Dispatchers.Default) {
                    rootEngine.disableServiceControlWithoutConfig()
                }
                activeEngine = null
                return@withLock resolvedState.copy(proxyRunning = false)
            }
            val wasRunning = withContext(Dispatchers.Default) {
                rootEngine.reconfigureServiceControl(
                    ProxyEngineStartRequest(resolvedState, selectedServer),
                )
            }
            activeEngine = rootEngine.takeIf { wasRunning }
            resolvedState.copy(proxyRunning = wasRunning)
        }
        mutableRootStatusWatchGeneration.update { generation -> generation + 1L }
        return appliedState
    }

    /**
     * Reads the live proxy status. A stopped proxy or disabled statistics always stop the
     * traffic stats service; only callers that confirm the running state for the visible UI
     * pass [restoreTrafficStats] to bring the notification back after the app process was
     * recreated while a ROOT runtime kept running.
     */
    suspend fun status(
        preferredRunMode: Int? = null,
        appState: AppState? = null,
        restoreTrafficStats: Boolean = false,
    ): ProxyEngineStatus = operationMutex.withLock {
        statusUnlocked(preferredRunMode, appState, restoreTrafficStats)
    }

    internal fun observeRootStatus(runMode: Int): Flow<ProxyEngineStatus> {
        val engine = rootEnginesByRunMode[runMode] ?: return emptyFlow()
        return engine.observeStatus().map { status ->
            normalizeRootRuntimeStatus(status, ::rootRunMode)
        }
    }

    private suspend fun startUnlocked(
        request: ProxyEngineStartRequest,
        explicitRestart: Boolean = false,
    ): ProxyEngineStatus = withContext(Dispatchers.Default) {
        val requestedEngine = request.appState.runMode.engine()
        val hasOtherActiveEngine = activeEngine.let { active -> active != null && active !== requestedEngine }
        var rootResumeChecked = false
        // A start that reaches an already running engine does not relaunch the core, so the
        // ports it is running with must be kept instead of being resolved again. The VPN tunnel
        // outlives this process, so a fresh process (no active engine yet) checks it as well.
        if (!explicitRestart && requestedEngine is VpnXrayEngine &&
            (activeEngine == null || activeEngine === requestedEngine)
        ) {
            val status = requestedEngine.status()
            if (status.running) {
                activeEngine = requestedEngine
                status.withTrafficStatsReconciled(request.appState, restore = true)
                return@withContext status.copy(appState = request.appState)
            }
        }
        if (shouldResumeRootBeforeResolvingPorts(explicitRestart, hasOtherActiveEngine, requestedEngine is RootModeEngine)) {
            requestedEngine as RootModeEngine
            requestedEngine.resumeIfRunning(request)?.let { status ->
                activeEngine = requestedEngine
                // The resumed runtime keeps its stats API, so the running notification is kept.
                status.withTrafficStatsReconciled(request.appState, restore = true)
                return@withContext status.copy(appState = request.appState)
            }
            rootResumeChecked = true
        }
        ProxyTrafficStatsService.reconcile(appContext, null)
        val resolvedBaseRequest = request.copy(appState = request.appState.withResolvedDynamicLocalProxyPort())
        val (resolvedRequest, trafficStatsRuntime) = resolvedBaseRequest.withTrafficStatsConfig()
        val nextEngine = resolvedRequest.appState.runMode.engine()
        val currentEngine = activeEngine ?: findEngineToStop(resolvedRequest.appState.runMode)
        val rootToRootRestart = explicitRestart && currentEngine is RootModeEngine && nextEngine is RootModeEngine
        if (currentEngine != null && currentEngine !== nextEngine && !rootToRootRestart) {
            if (currentEngine is RootModeEngine) currentEngine.shutdown() else currentEngine.stop()
        }
        activeEngine = nextEngine
        runCatching {
            when {
                explicitRestart && nextEngine is RootModeEngine -> nextEngine.restart(resolvedRequest)
                explicitRestart && nextEngine is VpnXrayEngine -> nextEngine.restart(resolvedRequest)
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

    private suspend fun shutdownRunModeUnlocked(runMode: Int): ProxyEngineStatus =
        withContext(Dispatchers.Default) {
            val engine = runMode.engine()
            activeEngine
                ?.takeIf { active -> active !== engine }
                ?.let { active -> if (active is RootModeEngine) active.shutdown() else active.stop() }
            val status = if (engine is RootModeEngine) engine.shutdown() else engine.stop()
            activeEngine = null
            ProxyTrafficStatsService.reconcile(appContext, null)
            status
        }

    private suspend fun findEngineToStop(preferredRunMode: Int?): AndroidModeProxyEngine? {
        val preferredEngine = preferredRunMode?.engine()
        return activeEngine
            ?: preferredEngine?.takeIf { it.status().running }
            ?: preferredEngine?.takeIf { it.ownsRootRuntime() }
            ?: withRootRuntimeProbe(preferredRunMode) {
                rootEngines.firstOrNull { engine -> engine.status().running }
            }
            ?: vpnXrayEngine.takeIf { it.status().running }
            ?: withRootRuntimeProbe(preferredRunMode) {
                rootEngines.firstOrNull { engine -> engine.ownsRuntime() }
            }
    }

    private suspend fun statusUnlocked(
        preferredRunMode: Int? = null,
        appState: AppState? = null,
        restoreTrafficStats: Boolean = false,
    ): ProxyEngineStatus = withContext(Dispatchers.Default) {
        var rootStatus: ProxyEngineStatus? = null

        suspend fun probeRoot(engine: RootModeEngine): ProxyEngineStatus {
            rootStatus?.let { return it }
            return normalizeRootRuntimeStatus(engine.status(), ::rootRunMode).also { rootStatus = it }
        }

        fun accept(status: ProxyEngineStatus, source: AndroidModeProxyEngine): ProxyEngineStatus {
            activeEngine = status.runMode?.let(rootEnginesByRunMode::get) ?: source
            return status.withTrafficStatsReconciled(appState, restoreTrafficStats)
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
            withRootRuntimeProbe(preferredRunMode) {
                val probeEngine = rootEngines.first()
                probeEngine to probeRoot(probeEngine)
            }?.let { (probeEngine, status) ->
                if (status.running) return@withContext accept(status, probeEngine)
                if (status.rootSnapshot != null && fallbackStatus?.rootSnapshot == null) {
                    fallbackStatus = status
                }
            }
        }

        if (active !== vpnXrayEngine && preferredRunMode != RunModeVpnService) {
            val status = vpnXrayEngine.status()
            if (status.running) return@withContext accept(status, vpnXrayEngine)
        }

        activeEngine = null
        (fallbackStatus ?: ProxyEngineStatus(running = false, runMode = preferredRunMode))
            .withTrafficStatsReconciled(appState, restoreTrafficStats)
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
        if (!appState.enableTrafficStatsNotification) {
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
            serverName = selectedServer.displayTitle(),
            apiTag = statsApiConfig.apiTag,
        )
        return request to runtime
    }

    private fun ProxyEngineStatus.withTrafficStatsReconciled(
        appState: AppState?,
        restore: Boolean,
    ): ProxyEngineStatus {
        if (!running) {
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
        // Status reads from background entry points must not start the foreground service;
        // starting it right before a stop request only causes start/stop churn.
        if (restore) {
            restoreTrafficStats(appState)
        }
        return this
    }

    /** Restores the traffic stats notification for a runtime the caller observed running. */
    internal fun restoreTrafficStats(appState: AppState) {
        if (!appState.enableTrafficStatsNotification) return
        ProxyTrafficStatsRuntimeStore.read(appContext)?.let { runtime ->
            ProxyTrafficStatsService.reconcile(appContext, runtime)
        }
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

internal suspend fun <T> withRootRuntimeProbe(
    preferredRunMode: Int?,
    probe: suspend () -> T,
): T? = if (preferredRunMode == RunModeVpnService) null else probe()

internal fun shouldResumeRootBeforeResolvingPorts(
    explicitRestart: Boolean,
    hasOtherActiveEngine: Boolean,
    requestedIsRoot: Boolean,
): Boolean = !explicitRestart && !hasOtherActiveEngine && requestedIsRoot

internal fun shouldUsePreResolvedRootStart(
    explicitRestart: Boolean,
    resumeChecked: Boolean,
    nextEngineIsRoot: Boolean,
): Boolean = !explicitRestart && resumeChecked && nextEngineIsRoot
