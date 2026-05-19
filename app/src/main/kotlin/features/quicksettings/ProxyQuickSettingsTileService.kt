// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.quicksettings

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import app.AppState
import app.MainActivity
import app.R
import app.modes.RunModeVpnService
import data.AndroidAppStateStore
import data.AppSettingsPreferences
import engine.proxy.AndroidProxyEngine
import features.logs.AndroidAppLogger
import features.proxy.server.usecase.ProxyServiceResult
import features.proxy.server.usecase.ProxyServiceUseCase
import features.settings.locale.localizedAppContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import system.AndroidRootShellGateway
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

class ProxyQuickSettingsTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operationInProgress = AtomicBoolean(false)
    private val stateStore by lazy { AndroidAppStateStore.get(applicationContext) }
    private val rootAccess by lazy { AndroidRootShellGateway() }
    private val proxyEngine by lazy {
        AndroidProxyEngine(
            context = applicationContext,
            rootAccess = rootAccess,
            requestVpnPermission = { intent ->
                launchActivityAndCollapse(intent)
                false
            },
        )
    }
    private val proxyServiceUseCase by lazy { ProxyServiceUseCase(proxyEngine) }

    override fun attachBaseContext(newBase: Context) {
        val languageMode = AppSettingsPreferences(newBase).load().languageMode
        super.attachBaseContext(newBase.localizedAppContext(languageMode))
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (!operationInProgress.compareAndSet(false, true)) return

        scope.launch {
            updateTile(processing = true)
            try {
                toggleProxy()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                AndroidAppLogger.error(LogTag, getString(R.string.quick_settings_tile_toggle_failed), error)
                showToast(error.message ?: getString(R.string.quick_settings_tile_toggle_failed))
            } finally {
                operationInProgress.set(false)
                refreshTileState()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshTile() {
        scope.launch { refreshTileState() }
    }

    private suspend fun refreshTileState() {
        val running = syncProxyRunningState()
        updateTile(running = running)
    }

    private suspend fun toggleProxy() {
        val running = syncProxyRunningState()
        val state = stateStore.state.value.copy(proxyRunning = running)
        if (!running && state.requiresVpnPermission()) {
            showToast(getString(R.string.quick_settings_tile_vpn_permission_required))
            launchActivityAndCollapse(VpnService.prepare(this))
            stateStore.update { currentState -> currentState.copy(proxyRunning = false) }
            return
        }

        val selectedServer = state.proxyServers.firstOrNull { server -> server.id == state.selectedProxyServerId }
        when (val result = proxyServiceUseCase.toggle(state, selectedServer)) {
            is ProxyServiceResult.Success -> {
                stateStore.update { currentState -> currentState.copy(proxyRunning = result.proxyRunning) }
                showToast(
                    if (result.proxyRunning) {
                        getString(R.string.proxy_server_list_service_started)
                    } else {
                        getString(R.string.proxy_server_list_service_stopped)
                    },
                )
            }

            ProxyServiceResult.MissingServer -> {
                showToast(getString(R.string.proxy_server_list_select_first))
            }

            is ProxyServiceResult.Failed -> {
                stateStore.update { currentState -> currentState.copy(proxyRunning = false) }
                AndroidAppLogger.error(LogTag, getString(R.string.quick_settings_tile_toggle_failed), result.error)
                showToast(result.error.message ?: getString(R.string.quick_settings_tile_toggle_failed))
            }
        }
    }

    private suspend fun syncProxyRunningState(): Boolean {
        val currentState = stateStore.state.value
        val running = runCatching { proxyEngine.status(currentState.runMode).running }
            .onFailure { error ->
                AndroidAppLogger.warn(LogTag, "Failed to read proxy status from quick settings tile", error)
            }
            .getOrElse { currentState.proxyRunning }
        if (currentState.proxyRunning != running) {
            stateStore.update { state -> state.copy(proxyRunning = running) }
        }
        return running
    }

    private fun AppState.requiresVpnPermission(): Boolean {
        return runMode == RunModeVpnService && VpnService.prepare(this@ProxyQuickSettingsTileService) != null
    }

    private fun updateTile(
        running: Boolean = stateStore.state.value.proxyRunning,
        processing: Boolean = false,
    ) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.quick_settings_tile_label)
        tile.state = when {
            processing -> Tile.STATE_UNAVAILABLE
            running -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                processing -> getString(R.string.quick_settings_tile_processing)
                running -> getString(R.string.quick_settings_tile_running)
                else -> getString(R.string.quick_settings_tile_stopped)
            }
        }
        tile.updateTile()
    }

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

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        private const val LogTag = "ProxyQuickSettingsTile"
    }
}
