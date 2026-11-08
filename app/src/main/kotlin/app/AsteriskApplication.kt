// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app

import features.resources.runtime.ResourceAutoUpdateScheduler
import features.resources.resourceAutoUpdateIntervalMillis
import features.resources.ResourceFileUseCase
import features.resources.ResourceFileUpdateCoordinator
import features.resources.ResourceFileUpdateRequest
import features.resources.runtime.AndroidResourceFileDownloadCancellation
import android.app.Application
import app.modes.RunModeVpnService
import features.boot.BootAutoConnect
import features.quicksettings.quickSettingsTileState
import features.quicksettings.refreshQuickSettingsTileUnlessShowing
import system.AndroidAppIconFetcher
import features.logs.AndroidAccessLogRepository
import features.logs.AndroidAsteriskdLogRepository
import features.logs.AndroidCoreLogRepository
import features.logs.AndroidLogcatRepository
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import data.AppSettingsPreferences
import data.AndroidAppStateStore
import engine.proxy.AndroidProxyEngine
import engine.proxy.LocalProxyRuntime
import engine.vpn.AsteriskVpnService
import engine.vpn.VpnSession
import app.effects.synchronizeProxyStatus
import app.effects.RunningConfigurationApplier
import engine.proxy.ProxyServiceResult
import engine.proxy.ProxyServiceUseCase
import features.logs.AndroidAppLogger
import features.proxy.server.model.isCompositeProxyServer
import features.resources.ResourceFileUpdateResult
import features.resources.isBusy
import ui.feedback.AndroidToastTipNotifier
import system.AndroidRootShellGateway
import features.subscription.runtime.AndroidSubscriptionFetcher
import features.subscription.runtime.AndroidSubscriptionScheduleGateway
import features.subscription.runtime.SubscriptionScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AsteriskApplication : Application(), SingletonImageLoader.Factory {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val stateStore: AndroidAppStateStore by lazy {
        AndroidAppStateStore.get(applicationContext)
    }
    /** Shared by every entry point so proxy and traffic stats operations are serialized. */
    internal val proxyEngine: AndroidProxyEngine by lazy {
        AndroidProxyEngine(
            context = applicationContext,
            rootAccess = AndroidRootShellGateway(),
        )
    }
    internal val subscriptionFetcher: AndroidSubscriptionFetcher by lazy {
        AndroidSubscriptionFetcher(applicationContext)
    }
    private val subscriptionScheduler: SubscriptionScheduler by lazy {
        SubscriptionScheduler(AndroidSubscriptionScheduleGateway(applicationContext))
    }

    private val resourceFileUseCase by lazy {
        ResourceFileUseCase(
            context = this,
            resourceFilePicker = { null },
            currentRunMode = { stateStore.state.value.runMode },
        )
    }
    internal val resourceFileUpdateCoordinator by lazy {
        ResourceFileUpdateCoordinator(
            scope = appScope,
            execute = { request ->
                when (request) {
                    is ResourceFileUpdateRequest.BuiltIn -> resourceFileUseCase.update(
                        kind = request.kind,
                        source = request.source,
                        options = request.options,
                        customResourceFiles = request.customResourceFiles,
                    )
                    is ResourceFileUpdateRequest.Custom -> resourceFileUseCase.updateCustom(
                        customFile = request.file,
                        options = request.options,
                        customResourceFiles = request.customResourceFiles,
                    )
                    is ResourceFileUpdateRequest.All -> resourceFileUseCase.update(
                        source = request.source,
                        options = request.options,
                        customResourceFiles = request.customResourceFiles,
                    )
                }
            },
            cancelRunning = AndroidResourceFileDownloadCancellation::cancel,
        )
    }

    /** Applies successful resource and subscription updates to a running core. */
    private val runningConfigurationApplier by lazy {
        RunningConfigurationApplier(
            scope = appScope,
            updatesBusy = resourceFileUpdateCoordinator.state.map { state -> state.isBusy },
            apply = ::applyConfigurationToRunningCore,
            reportFailure = { error ->
                AndroidAppLogger.error(RunningConfigurationLogTag, "Automatic configuration apply failed", error)
            },
        )
    }

    /**
     * Every subscription update commits its servers together with the group's update time, from
     * the UI, the scheduled worker and the broadcast alike. Such a commit is applied when it
     * changed what the core runs: the selected server, or any server a selected chain or
     * strategy group resolves through. Manual edits keep their current behavior.
     */
    private fun applySubscriptionUpdates() {
        appScope.launch {
            var previous: AppState? = null
            stateStore.state.collect { current ->
                val prior = previous
                previous = current
                if (prior != null && current.isSubscriptionCommitChangingRuntimeFrom(prior)) {
                    runningConfigurationApplier.request()
                }
            }
        }
    }

    /** An explicit restart of a core that is still running; a stopped core is left alone. */
    private suspend fun applyConfigurationToRunningCore() {
        val state = stateStore.state.value
        val server = state.proxyServers.firstOrNull { it.id == state.selectedProxyServerId } ?: return
        val running = runCatching { proxyEngine.status(state.runMode, state).running }.getOrNull()
        if (running != true) return
        val useCase = ProxyServiceUseCase(proxyEngine)
        when (val result = useCase.applyConfiguration(state, server)) {
            is ProxyServiceResult.Success -> {
                AndroidAppLogger.info(RunningConfigurationLogTag, "Applied updated configuration")
                stateStore.update { current ->
                    current.copy(
                        proxyRunning = result.proxyRunning,
                        localProxyPort = result.appState?.localProxyPort ?: current.localProxyPort,
                    )
                }
            }
            ProxyServiceResult.MissingServer -> Unit
            is ProxyServiceResult.Failed -> {
                // A failed restart may already have stopped the core, so read back what runs.
                runCatching { proxyEngine.status(state.runMode, state).running }.getOrNull()?.let { running ->
                    stateStore.update { current -> current.copy(proxyRunning = running) }
                }
                AndroidToastTipNotifier(applicationContext).showError(
                    result.error,
                    getString(R.string.proxy_service_auto_apply_failed),
                )
            }
        }
    }

    /**
     * Brings the app state in line when a tunnel ends in the service process, whether it was
     * stopped, revoked or its process died. The session watch reports the end, so this costs
     * nothing while the tunnel runs; a tunnel that is up when this process starts is watched
     * from here as well.
     */
    private fun synchronizeOnVpnSessionEnd() {
        appScope.launch {
            VpnSession.ended.collect {
                // Only the VPN mode is resynchronized: other modes are probed through ROOT.
                if (stateStore.state.value.runMode != RunModeVpnService) return@collect
                synchronizeProxyStatus(
                    currentState = { stateStore.state.value },
                    readStatus = { snapshot ->
                        runCatching {
                            proxyEngine.status(snapshot.runMode, snapshot, restoreTrafficStats = true)
                        }.getOrNull()
                    },
                    updateAppState = stateStore::update,
                )
            }
        }
        appScope.launch(Dispatchers.IO) { VpnSession.watch(applicationContext) }
    }

    /**
     * Asks SystemUI to rebind the quick settings tile whenever a state source in this process
     * changes it; the first collection is the refresh at process start. The language is included
     * because opening the panel no longer refreshes an active tile. A `proxyRunning` the tile
     * writes back itself is skipped, since the tile shows it without reading the status again.
     */
    private fun refreshQuickSettingsTileOnProxyStateChange() {
        appScope.launch {
            stateStore.state
                .map { state -> state.quickSettingsTileState() }
                .distinctUntilChanged()
                .collect { tileState -> refreshQuickSettingsTileUnlessShowing(applicationContext, tileState) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The `:service` process only executes: the VPN Service with its core and the traffic
        // statistics service. The state store, the schedulers, WorkManager and the core log
        // repositories belong to this app process; `:service` only appends its own app log.
        if (AsteriskVpnService.isServiceProcess(this)) {
            AndroidLogcatRepository.initializeServiceProcess(this)
            return
        }
        // HTTP fetches here go through the tunnel's local proxy, which the service publishes.
        LocalProxyRuntime.attachExternalSource { VpnSession.runningLocalProxyOptions(applicationContext) }
        synchronizeOnVpnSessionEnd()
        applySubscriptionUpdates()
        appScope.launch {
            resourceFileUpdateCoordinator.results.collect { result ->
                if (result is ResourceFileUpdateResult.Success) runningConfigurationApplier.request()
            }
        }
        refreshQuickSettingsTileOnProxyStateChange()
        appScope.launch {
            val scheduler = ResourceAutoUpdateScheduler(applicationContext)
            stateStore.state
                .map { state -> resourceAutoUpdateIntervalMillis(state.enableResourceAutoUpdate, state.resourceAutoUpdateInterval) }
                .distinctUntilChanged()
                .collect(scheduler::reconcile)
        }
        AppSettingsPreferences(applicationContext).getOrCreateSubscriptionHwid()
        AndroidLogcatRepository.initialize(applicationContext)
        AndroidCoreLogRepository.initialize(applicationContext)
        AndroidAccessLogRepository.initialize(applicationContext)
        AndroidAsteriskdLogRepository.initialize(applicationContext)
        appScope.launch {
            stateStore.state
                .map { state ->
                    state.enableAutoConnect && state.runMode == RunModeVpnService
                }
                .distinctUntilChanged()
                .collect { enabled ->
                    BootAutoConnect.setComponentEnabled(applicationContext, enabled)
                }
        }
        appScope.launch {
            stateStore.state
                .map { state ->
                    state.subscriptionGroups.map { group ->
                        SubscriptionScheduleKey(
                            id = group.id,
                            url = group.url,
                            interval = group.updateInterval,
                            enabled = group.enabled,
                        )
                    }
                }
                .distinctUntilChanged()
                .collect {
                    subscriptionScheduler.reconcile(stateStore.state.value.subscriptionGroups)
                }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(AndroidAppIconFetcher.Factory(this@AsteriskApplication))
                add(AndroidAppIconFetcher.CacheKeyer())
            }
            .build()
    }

    private data class SubscriptionScheduleKey(
        val id: Int,
        val url: String,
        val interval: String,
        val enabled: Boolean,
    )
}

private fun AppState.isSubscriptionCommitChangingRuntimeFrom(previous: AppState): Boolean {
    val committed = subscriptionGroups.any { group ->
        previous.subscriptionGroups.any { prior ->
            prior.id == group.id && prior.lastUpdatedAtMillis != group.lastUpdatedAtMillis
        }
    }
    if (!committed) return false
    if (selectedProxyServerId != previous.selectedProxyServerId) return true
    val selected = proxyServers.firstOrNull { server -> server.id == selectedProxyServerId }
    val priorSelected = previous.proxyServers.firstOrNull { server -> server.id == selectedProxyServerId }
    if (selected != priorSelected) return true
    // Chains and strategy groups resolve their members from the whole server list.
    return selected?.server?.isCompositeProxyServer() == true && proxyServers != previous.proxyServers
}

private const val RunningConfigurationLogTag = "RunningConfiguration"
