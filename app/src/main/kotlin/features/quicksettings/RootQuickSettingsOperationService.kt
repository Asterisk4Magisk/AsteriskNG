// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.quicksettings

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.R
import app.modes.RunModeVpnService
import features.logs.AndroidAppLogger
import features.settings.locale.localizedFromSavedLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal class RootQuickSettingsOperationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.localizedFromSavedLanguage())
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            promoteToForeground()
        } catch (error: Exception) {
            try {
                ProxyQuickSettingsTileService.reportToggleFailure(this, error)
            } finally {
                stopSelf(startId)
                ProxyQuickSettingsTileService.finishToggle()
            }
            return START_NOT_STICKY
        }

        val runMode = intent?.getIntExtra(RunModeExtra, RunModeVpnService) ?: RunModeVpnService
        scope.launch {
            try {
                if (intent != null && intent.hasExtra(ShouldRunExtra) && runMode != RunModeVpnService) {
                    val shouldRun = intent.getBooleanExtra(ShouldRunExtra, false)
                    runCatching {
                        AndroidAppLogger.info(LogTag, "root_tile_foreground runMode=$runMode shouldRun=$shouldRun")
                    }
                    ProxyQuickSettingsTileService.runToggle(this@RootQuickSettingsOperationService, runMode, shouldRun)
                } else {
                    ProxyQuickSettingsTileService.finishToggle()
                }
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun promoteToForeground() {
        val title = getString(R.string.quick_settings_tile_label)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ChannelId, title, NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(R.drawable.ic_qs_proxy)
            .setContentTitle(title)
            .setContentText(getString(R.string.quick_settings_tile_processing))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NotificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NotificationId, notification)
        }
    }

    companion object {
        private const val LogTag = "RootQuickSettingsOperation"
        private const val ChannelId = "root_quick_settings_operation"
        private const val NotificationId = 20819
        private const val RunModeExtra = "run_mode"
        private const val ShouldRunExtra = "should_run"

        fun start(context: Context, runMode: Int, shouldRun: Boolean) {
            val request = Intent(context, RootQuickSettingsOperationService::class.java)
                .putExtra(RunModeExtra, runMode)
                .putExtra(ShouldRunExtra, shouldRun)
            ContextCompat.startForegroundService(context, request)
        }
    }
}
