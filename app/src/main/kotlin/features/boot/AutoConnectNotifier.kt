// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.boot

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import app.R

/**
 * Explains a boot start that cannot proceed because the VPN consent is missing.
 *
 * A receiver running in the background may not launch the system consent dialog, so the
 * notification is the only sanctioned way to hand that step back to the user. It is posted
 * only when notifications are allowed; otherwise the skipped start is left to the log entry
 * the receiver writes. The settings switch asks for the consent up front, so this is the
 * fallback for a consent revoked later.
 */
internal object AutoConnectNotifier {
    private const val ChannelId = "auto-connect"
    private const val NotificationId = 6

    fun showVpnPermissionRequired(context: Context) {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                ChannelId,
                appContext.getString(R.string.settings_start_on_boot),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val contentIntent = PendingIntent.getActivity(
            appContext,
            0,
            BootAutoConnect.consentIntent(appContext),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(appContext, ChannelId)
            .setSmallIcon(R.drawable.ic_qs_proxy)
            .setContentTitle(appContext.getString(R.string.settings_start_on_boot))
            .setContentText(appContext.getString(R.string.auto_connect_permission_text))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        manager.notify(NotificationId, notification)
    }
}
