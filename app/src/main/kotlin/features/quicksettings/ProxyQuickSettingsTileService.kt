// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.quicksettings

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import app.AppState
import app.AsteriskApplication
import app.MainActivity
import app.R
import app.modes.RunModeVpnService
import data.AndroidAppStateStore
import features.logs.AndroidAppLogger
import engine.proxy.ProxyServiceResult
import engine.proxy.ProxyServiceUseCase
import engine.vpn.VpnSession
import features.settings.locale.localizedFromSavedLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ui.feedback.rootOperationTipMessageOrNull
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

class ProxyQuickSettingsTileService : TileService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listeningJob: Job? = null
    private var refreshJob: Job? = null
    private val stateStore by lazy { AndroidAppStateStore.get(applicationContext) }
    private val proxyEngine by lazy { (application as AsteriskApplication).proxyEngine }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.localizedFromSavedLanguage())
    }

    override fun onStartListening() {
        super.onStartListening()
        activeService = WeakReference(this)
        // A normal tile is bound whenever the panel opens, including after process death.
        // Keep it unavailable until its first live status has replaced SystemUI's cached state.
        if (operationInProgress.get()) {
            showProcessing()
        } else {
            updateTile(running = null)
        }
        listeningJob?.cancel()
        listeningJob = serviceScope.launch {
            // A surviving VPN may end before the app has ever shown it as running.
            launch {
                VpnSession.ended.collect {
                    if (stateStore.state.value.runMode == RunModeVpnService) refreshTile()
                }
            }
            stateStore.state
                .map { state -> state.quickSettingsTileState() }
                .distinctUntilChanged()
                .collect { refreshTile() }
        }
    }

    override fun onStopListening() {
        clearActiveService()
        listeningJob?.cancel()
        listeningJob = null
        refreshJob?.cancel()
        refreshJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // Capture what the user saw before any asynchronous status refresh can change it.
        val shouldRun = when (qsTile?.state) {
            Tile.STATE_ACTIVE -> false
            Tile.STATE_INACTIVE -> true
            else -> return
        }
        val runMode = stateStore.state.value.runMode
        if (!operationInProgress.compareAndSet(false, true)) return
        operationSequence.incrementAndGet()
        refreshJob?.cancel()

        if (runMode != RunModeVpnService) {
            try {
                RootQuickSettingsOperationService.start(this, runMode, shouldRun)
            } catch (error: Exception) {
                try {
                    reportToggleFailure(this, error)
                } finally {
                    finishToggle()
                }
            }
            return
        }

        operationScope.launch {
            runToggle(this@ProxyQuickSettingsTileService, runMode, shouldRun, ::launchActivityAndCollapse)
        }
    }

    override fun onDestroy() {
        clearActiveService()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun clearActiveService() {
        if (activeService?.get() === this) {
            activeService = null
        }
    }

    private fun refreshTile(): Job {
        refreshJob?.cancel()
        return serviceScope.launch { refreshTileState() }.also { refreshJob = it }
    }

    private suspend fun refreshTileState() {
        val sequence = operationSequence.get()
        if (operationInProgress.get()) return
        val running = readProxyRunningState()
        // A toggle that began while the status was read owns the tile; the status is stale.
        if (operationInProgress.get()) return
        if (operationSequence.get() != sequence) return
        updateTile(running = running)
    }

    private suspend fun readProxyRunningState(): Boolean? {
        val runMode = stateStore.state.value.runMode
        return try {
            if (runMode == RunModeVpnService) {
                withContext(Dispatchers.IO) { VpnSession.readRunning(applicationContext) }
            } else {
                proxyEngine.readRootStatus(runMode).running
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AndroidAppLogger.warn(LogTag, "Failed to read proxy status from quick settings tile", error)
            null
        }
    }

    private fun showProcessing() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val tile = qsTile ?: return
            tile.subtitle = getString(R.string.quick_settings_tile_processing)
            tile.updateTile()
        }
    }

    private fun updateTile(running: Boolean?) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.quick_settings_tile_label)
        tile.state = when {
            running == null -> Tile.STATE_UNAVAILABLE
            running -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                running == null -> null
                running -> stateStore.state.value.quickSettingsActiveNodeName()
                    ?: getString(R.string.quick_settings_tile_running)
                else -> getString(R.string.quick_settings_tile_stopped)
            }
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchActivityAndCollapse(intent: Intent?) {
        val targetIntent = (intent ?: Intent(this, MainActivity::class.java)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                targetIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(targetIntent)
        }
    }

    companion object {
        private const val LogTag = "ProxyQuickSettingsTile"

        private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val operationInProgress = AtomicBoolean(false)
        private val operationSequence = AtomicLong(0)
        private var activeService: WeakReference<ProxyQuickSettingsTileService>? = null

        internal suspend fun runToggle(
            context: Context,
            runMode: Int,
            shouldRun: Boolean,
            openVpnPermission: (Intent) -> Unit = {},
        ) {
            var finalRunning: Boolean? = null
            try {
                val stateStore = AndroidAppStateStore.get(context.applicationContext)
                val state = stateStore.state.value
                if (state.runMode != runMode) return

                activeService?.get()?.showProcessing()
                finalRunning = context.setProxyRunning(stateStore, state, shouldRun, openVpnPermission)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                reportToggleFailure(context, error)
            } finally {
                finishToggle(finalRunning)?.join()
            }
        }

        internal fun finishToggle(running: Boolean? = null): Job? {
            operationInProgress.set(false)
            val service = activeService?.get() ?: return null
            service.refreshJob?.cancel()
            service.updateTile(running)
            return if (running == null) service.refreshTile() else null
        }

        private suspend fun Context.setProxyRunning(
            stateStore: AndroidAppStateStore,
            state: AppState,
            shouldRun: Boolean,
            openVpnPermission: (Intent) -> Unit,
        ): Boolean? {
            if (shouldRun && state.runMode == RunModeVpnService) {
                VpnService.prepare(this)?.let { intent ->
                    showToast(getString(R.string.quick_settings_tile_vpn_permission_required))
                    openVpnPermission(intent)
                    stateStore.update { currentState -> currentState.copy(proxyRunning = false) }
                    return null
                }
            }

            val proxyServiceUseCase = ProxyServiceUseCase(
                proxyEngine = (applicationContext as AsteriskApplication).proxyEngine,
                requestVpnPermission = { intent ->
                    openVpnPermission(intent)
                    false
                },
            )
            val result = if (shouldRun) {
                val selectedServer = state.proxyServers.firstOrNull { server -> server.id == state.selectedProxyServerId }
                proxyServiceUseCase.start(state, selectedServer)
            } else {
                proxyServiceUseCase.stop(state.runMode)
            }
            return when (result) {
                is ProxyServiceResult.Success -> {
                    stateStore.update { currentState ->
                        currentState.copy(
                            proxyRunning = result.proxyRunning,
                            localProxyPort = result.appState?.localProxyPort ?: currentState.localProxyPort,
                        )
                    }
                    showToast(
                        if (result.proxyRunning) {
                            getString(R.string.proxy_server_list_service_started)
                        } else {
                            getString(R.string.proxy_server_list_service_stopped)
                        },
                    )
                    result.proxyRunning.takeIf { stateStore.state.value.runMode == state.runMode }
                }

                ProxyServiceResult.MissingServer -> {
                    showToast(getString(R.string.proxy_server_list_select_first))
                    null
                }

                is ProxyServiceResult.Failed -> {
                    showToast(result.error.quickSettingsErrorMessage(this))
                    null
                }
            }
        }

        internal fun reportToggleFailure(context: Context, error: Exception) {
            AndroidAppLogger.warn(LogTag, "Failed to toggle proxy from quick settings tile", error)
            context.showToast(error.quickSettingsErrorMessage(context))
        }

        private fun Throwable.quickSettingsErrorMessage(context: Context): String {
            return rootOperationTipMessageOrNull(context) ?: message
                ?: context.getString(R.string.quick_settings_tile_toggle_failed)
        }

        private fun Context.showToast(message: String) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        /** Runtime broadcasts only refresh a bound tile; the next panel opening reads the rest. */
        internal fun refreshIfListening() {
            activeService?.get()?.refreshTile()
        }
    }
}
