// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.MainActivity

/**
 * The launcher entry lives on an alias so it can be disabled to hide the
 * app from the launcher without breaking deep links, shortcuts or the
 * quick settings tile.
 *
 * Dialling `*#*#260517#*#*` in any dialer opens the app while the icon is hidden.
 */
internal object AndroidLauncherIcon {

    private const val ALIAS = "app.ui.LauncherActivityAlias"

    /**
     * AsteriskNG initial commit date (2026-05-17). Encoded into the dial
     * code that the SecretCodeReceiver listens for, so the user can
     * still open the app after hiding the launcher icon.
     */
    const val SECRET_CODE = "260517"

    const val DIAL_CODE = "*#*#$SECRET_CODE#*#*"

    /**
     * API < 29 cannot reliably deliver the recovery broadcast to
     * SecretCodeReceiver (legacy Telephony.SECRET_CODE requires
     * CONTROL_INCALL_EXPERIENCE on the sender), so hiding is never applied
     * there, including when a restored backup or stored preference asks for it.
     */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun isHidden(context: Context): Boolean {
        val state = context.packageManager.getComponentEnabledSetting(component(context))
        return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    fun setHidden(context: Context, hidden: Boolean) {
        val hide = hidden && isSupported
        if (isHidden(context) == hide) return
        context.packageManager.setComponentEnabledSetting(
            component(context),
            if (hide) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }

    /**
     * Explicit launch intent for notifications. `getLaunchIntentForPackage`
     * returns null while the launcher alias is disabled.
     */
    fun launchIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    private fun component(context: Context): ComponentName =
        ComponentName(context.packageName, ALIAS)
}
