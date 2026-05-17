package features.resources.runtime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import app.R

internal class AndroidResourceFileDownloadNotifier(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private val contentIntent = appContext.packageManager
        .getLaunchIntentForPackage(appContext.packageName)
        ?.let { intent ->
            PendingIntent.getActivity(
                appContext,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    private val cancelIntent = PendingIntent.getBroadcast(
        appContext,
        0,
        Intent(appContext, AndroidResourceFileDownloadCancelReceiver::class.java)
            .setAction(ResourceFileDownloadCancelAction)
            .setPackage(appContext.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    private var lastProgressNotifyAt = 0L

    init {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                ChannelId,
                appContext.getString(R.string.resource_file_download_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    fun showProgress(fileName: String, progress: Int?, force: Boolean = false) {
        if (!canNotify()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastProgressNotifyAt < ProgressNotifyIntervalMillis) {
            return
        }
        lastProgressNotifyAt = now
        notificationManager.notify(
            ResourceFileDownloadNotificationId,
            baseBuilder(
                icon = android.R.drawable.stat_sys_download,
                title = appContext.getString(R.string.resource_file_download_notification_title),
                text = appContext.getString(R.string.resource_file_download_notification_progress, fileName),
            )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setProgress(100, progress?.coerceIn(0, 100) ?: 0, progress == null)
                .addAction(
                    Notification.Action.Builder(
                        Icon.createWithResource(appContext, android.R.drawable.ic_menu_close_clear_cancel),
                        appContext.getString(R.string.resource_file_download_notification_cancel),
                        cancelIntent,
                    ).build(),
                )
                .build(),
        )
    }

    fun showComplete() {
        if (!canNotify()) return
        notificationManager.notify(
            ResourceFileDownloadNotificationId,
            baseBuilder(
                icon = android.R.drawable.stat_sys_download_done,
                title = appContext.getString(R.string.resource_file_download_notification_title),
                text = appContext.getString(R.string.resource_file_download_notification_complete),
            )
                .setAutoCancel(true)
                .build(),
        )
    }

    fun showCancelled() {
        if (!canNotify()) return
        notificationManager.notify(
            ResourceFileDownloadNotificationId,
            baseBuilder(
                icon = android.R.drawable.stat_notify_error,
                title = appContext.getString(R.string.resource_file_download_notification_title),
                text = appContext.getString(R.string.resource_file_download_notification_cancelled),
            )
                .setAutoCancel(true)
                .build(),
        )
    }

    fun showFailed(message: String) {
        if (!canNotify()) return
        notificationManager.notify(
            ResourceFileDownloadNotificationId,
            baseBuilder(
                icon = android.R.drawable.stat_notify_error,
                title = appContext.getString(R.string.resource_file_download_notification_title),
                text = appContext.getString(R.string.resource_file_download_notification_failed, message),
            )
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun baseBuilder(
        icon: Int,
        title: String,
        text: String,
    ): Notification.Builder {
        return Notification.Builder(appContext, ResourceFileDownloadNotificationChannelId)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setLocalOnly(true)
    }

    private fun canNotify(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        private const val ChannelId = ResourceFileDownloadNotificationChannelId
        private const val ProgressNotifyIntervalMillis = ResourceFileDownloadProgressNotifyIntervalMillis
    }
}
