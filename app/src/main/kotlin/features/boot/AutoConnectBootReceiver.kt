// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.modes.RunModeVpnService
import data.AndroidAppStateStore
import data.AppSettingsPreferences
import features.logs.AndroidAppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Starts the VPN Service after boot when the user enabled auto connect, and restores it after
 * an app update when it was running before.
 *
 * Only the foreground-service hand-off happens here; compiling the configuration and
 * establishing the tunnel stay in [engine.vpn.AsteriskVpnService], exactly like a manual
 * start. The receiver is disabled in the manifest and enabled again from
 * [BootAutoConnect.setComponentEnabled] so users who never opt in pay no boot wake-up.
 */
class AutoConnectBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        scope.launch {
            try {
                if (!shouldStart(appContext, action)) return@launch
                if (BootAutoConnect.needsVpnPermission(appContext)) {
                    AndroidAppLogger.warn(
                        LogTag,
                        "Auto connect skipped because the VPN permission is missing",
                    )
                    AutoConnectNotifier.showVpnPermissionRequired(appContext)
                    return@launch
                }
                BootAutoConnect.startService(appContext)
                AndroidAppLogger.info(LogTag, "Auto connect handed the VPN service to the foreground")
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                AndroidAppLogger.error(LogTag, "Auto connect failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun shouldStart(context: Context, action: String): Boolean {
        val state = AndroidAppStateStore.get(context).state.value
        if (!state.enableAutoConnect) {
            // The preference was turned off while this receiver was still enabled.
            BootAutoConnect.setComponentEnabled(context, false)
            return false
        }
        // ROOT modes have their own boot entry and must never be started through the VPN path.
        if (state.runMode != RunModeVpnService) return false
        if (state.proxyServers.none { server -> server.id == state.selectedProxyServerId }) {
            AndroidAppLogger.warn(LogTag, "Auto connect skipped because no proxy server is selected")
            return false
        }
        // An update only restores a tunnel that was up when the old version was killed; it must
        // not start one the user had stopped.
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED &&
            !AppSettingsPreferences(context).isVpnSessionActive()
        ) {
            return false
        }
        return true
    }

    private companion object {
        private const val LogTag = "AutoConnectBoot"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
