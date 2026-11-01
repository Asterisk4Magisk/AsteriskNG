// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import features.logs.AndroidAppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Receives the start that the VPN Service hands back after a boot resume or an always-on start.
 *
 * The service runs in its own process without the app state, so it only promotes itself to the
 * foreground and lets this process compile the configuration like any manual start.
 */
class VpnResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        scope.launch {
            try {
                BootAutoConnect.resume(appContext)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                AndroidAppLogger.error(LogTag, "Failed to resume the VPN Service", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        private const val LogTag = "VpnResumeReceiver"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
