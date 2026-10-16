// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.AsteriskApplication
import app.R
import kotlinx.coroutines.launch
import ui.feedback.AndroidToastTipNotifier

/**
 * Restores the launcher icon when the user dials `*#*#260517#*#*`.
 *
 * The broadcast is dispatched system-wide by the dialer, not by our
 * package, so we can still recover when the launcher entry is hidden.
 *
 * Persists the preference change before finishing the broadcast so the
 * platform may safely reclaim the receiver process at any moment after
 * `pendingResult.finish()`.
 */
class SecretCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SecretCodeActions) return
        if (intent.data?.host != AndroidLauncherIcon.SECRET_CODE) return
        val application = context.applicationContext as? AsteriskApplication
        // The stored preference may still ask to hide the icon while the alias
        // is enabled, e.g. right after a backup restore, so reset it too.
        val hiddenInState = application?.stateStore?.state?.value?.hideLauncherIcon == true
        if (!AndroidLauncherIcon.isHidden(context) && !hiddenInState) return

        val pendingResult = goAsync()
        if (application == null) {
            try {
                AndroidLauncherIcon.setHidden(context, false)
            } finally {
                pendingResult.finish()
            }
            return
        }
        application.appScope.launch {
            try {
                AndroidLauncherIcon.setHidden(context, false)
                val current = application.stateStore.state.value
                if (current.hideLauncherIcon) {
                    application.stateStore.update { it.copy(hideLauncherIcon = false) }
                    application.stateStore.awaitPersistence()
                }
                AndroidToastTipNotifier(context)
                    .show(context.getString(R.string.settings_launcher_icon_restored))
            } finally {
                pendingResult.finish()
            }
        }
    }
}

private val SecretCodeActions = setOf(
    "android.provider.Telephony.SECRET_CODE",
    "android.telephony.action.SECRET_CODE",
)
