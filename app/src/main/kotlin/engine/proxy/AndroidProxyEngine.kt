// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.proxy

import android.content.Context
import android.content.Intent
import app.AppState
import app.ProxyServerState
import app.R
import app.modes.RunModeTun2Socks
import app.modes.RunModeTproxy
import app.modes.RunModeVpnService
import engine.stats.ProxyTrafficStatsRuntime
import engine.stats.ProxyTrafficStatsRuntimeStore
import engine.stats.ProxyTrafficStatsService
import engine.stats.XrayStatsApiListenAddress
import engine.stats.resolveXrayStatsApiPort
import engine.stats.xrayStatsApiExcludedPorts
import engine.proxy.mode.AndroidModeProxyEngine
import engine.root.RootModeEngine
import engine.tproxy.TproxyRootRunner
import engine.tproxy.buildTproxyStartConfig
import engine.tun2socks.Tun2SocksRootRunner
import engine.tun2socks.buildTun2SocksStartConfig
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
    private val tproxyEngine = RootModeEngine(
        context = appContext,
        rootAccess = rootAccess,
        runner = TproxyRootRunner(rootAccess),
        runMode = RunModeTproxy,
        rootRequiredErrorResId = R.string.error_tproxy_root_required,
        startFailedErrorResId = R.string.error_tproxy_start_failed,
        modeName = "TPROXY",
        logTag = "TproxyEngine",
        buildConfig = { rootContext -> rootContext.buildTproxyStartConfig() },
    )
    private val tun2SocksEngine = RootModeEngine(
        context = appContext,
        rootAccess = rootAccess,
        runner = Tun2SocksRootRunner(rootAccess),
        runMode = RunModeTun2Socks,
        rootRequiredErrorResId = R.string.error_tun2socks_root_required,
        startFailedErrorResId = R.string.error_tun2socks_start_failed,
        modeName = "TUN2SOCKS",
        logTag = "Tun2SocksEngine",
        buildConfig = { rootContext -> rootContext.buildTun2SocksStartConfig() },
    )
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
        startUnlocked(request)
    }

    suspend fun status(
        preferredRunMode: Int? = null,
        appState: AppState? = null,
    ): ProxyEngineStatus = operationMutex.withLock {
        statusUnlocked(preferredRunMode, appState)
    }

    private suspend fun startUnlocked(request: ProxyEngineStartRequest): ProxyEngineStatus = withContext(Dispatchers.Default) {
        ProxyTrafficStatsService.reconcile(appContext, null)
        val resolvedBaseRequest = request.copy(appState = request.appState.withResolvedDynamicLocalProxyPort())
        val (resolvedRequest, trafficStatsRuntime) = resolvedBaseRequest.withTrafficStatsConfig()
        val nextEngine = when (resolvedRequest.appState.runMode) {
            RunModeTproxy -> tproxyEngine
            RunModeTun2Socks -> tun2SocksEngine
            else -> vpnXrayEngine
        }
        val currentEngine = activeEngine ?: findEngineToStop(resolvedRequest.appState.runMode)
        if (currentEngine != null && currentEngine !== nextEngine) {
            currentEngine.stop()
        }
        activeEngine = nextEngine
        runCatching {
            nextEngine.start(resolvedRequest)
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
            ?: tproxyEngine.takeIf { it.status().running }
            ?: tun2SocksEngine.takeIf { it.status().running }
            ?: vpnXrayEngine.takeIf { it.status().running }
            ?: tproxyEngine.takeIf { it.ownsRuntime() }
            ?: tun2SocksEngine.takeIf { it.ownsRuntime() }
    }

    private suspend fun statusUnlocked(
        preferredRunMode: Int? = null,
        appState: AppState? = null,
    ): ProxyEngineStatus = withContext(Dispatchers.Default) {
        val activeStatus = activeEngine?.status()
        if (activeStatus?.running == true) {
            return@withContext activeStatus
                .withTrafficStatsReconciled(appState)
        }

        var fallbackStatus = activeStatus
        preferredRunMode?.engine()?.let { preferredEngine ->
            val preferredStatus = preferredEngine.status()
            if (preferredStatus.running) {
                activeEngine = preferredEngine
                return@withContext preferredStatus
                    .withTrafficStatsReconciled(appState)
            }
            fallbackStatus = preferredStatus
        }

        listOf(tproxyEngine, tun2SocksEngine, vpnXrayEngine)
            .filterNot { engine -> engine.runMode == preferredRunMode }
            .forEach { engine ->
                val status = engine.status()
                if (status.running) {
                    activeEngine = engine
                    return@withContext status
                        .withTrafficStatsReconciled(appState)
                }
            }

        activeEngine = null
        (fallbackStatus ?: ProxyEngineStatus(running = false, runMode = preferredRunMode))
            .withTrafficStatsReconciled(appState)
    }

    private fun Int.engine(): AndroidModeProxyEngine {
        return when (this) {
            RunModeTproxy -> tproxyEngine
            RunModeTun2Socks -> tun2SocksEngine
            else -> vpnXrayEngine
        }
    }

    private suspend fun AndroidModeProxyEngine.ownsRootRuntime(): Boolean {
        return this is RootModeEngine<*> && ownsRuntime()
    }

    private fun ProxyEngineStartRequest.withTrafficStatsConfig(): Pair<ProxyEngineStartRequest, ProxyTrafficStatsRuntime?> {
        if (!appState.enableTrafficStatsNotification || appState.runMode != RunModeVpnService) {
            return this to null
        }
        val port = resolveXrayStatsApiPort(
            preferredPort = ProxyTrafficStatsRuntimeStore.readPort(appContext),
            excludedPorts = appState.xrayStatsApiExcludedPorts(),
        )
        val runtime = ProxyTrafficStatsRuntime(
            listenAddress = XrayStatsApiListenAddress,
            port = port,
            serverName = selectedServer.trafficStatsServerName(),
        )
        return copy(
            xrayStatsApiListenAddress = runtime.listenAddress,
            xrayStatsApiPort = runtime.port,
        ) to runtime
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

private fun ProxyServerState.trafficStatsServerName(): String {
    val info = server.getInfo()
    return info.remarks.ifBlank { info.protocol }
}
