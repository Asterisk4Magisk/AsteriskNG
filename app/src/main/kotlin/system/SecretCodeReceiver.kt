// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Opens the main screen when the user dials `*#*#260517#*#*`.
 *
 * The broadcast is dispatched system-wide by the dialer, not by our
 * package, so the app stays reachable while the launcher entry is hidden.
 * The launcher icon itself is only shown again when the user turns the
 * setting off.
 */
class SecretCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SecretCodeActions) return
        if (intent.data?.host != AndroidLauncherIcon.SECRET_CODE) return
        context.startActivity(AndroidLauncherIcon.launchIntent(context))
    }
}

private val SecretCodeActions = setOf(
    "android.provider.Telephony.SECRET_CODE",
    "android.telephony.action.SECRET_CODE",
)
