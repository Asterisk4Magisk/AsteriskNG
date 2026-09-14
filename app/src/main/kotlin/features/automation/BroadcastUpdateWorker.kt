// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.automation

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.AsteriskApplication
import app.R
import app.resourceFileUpdateSource
import features.logs.AndroidAppLogger
import features.resources.ResourceFileUpdateRequest
import features.resources.ResourceFileUpdateResult
import features.resources.ResourceFileUpdateTarget
import features.resources.resourceFileUpdateOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

internal class BroadcastUpdateWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val title = applicationContext.getString(R.string.broadcast_update_notification_title)
        manager.createNotificationChannel(NotificationChannel(ChannelId, title, NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(applicationContext, ChannelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(
                null,
                applicationContext.getString(android.R.string.cancel),
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            ).build())
            .build()
        val notificationId = if (inputData.getString(BroadcastUpdateKindKey) == BroadcastUpdateKind.Subscription.name) 4101 else 4102
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    override suspend fun doWork(): Result {
        val application = applicationContext as AsteriskApplication
        val kind = BroadcastUpdateKind.entries.firstOrNull {
            it.name == inputData.getString(BroadcastUpdateKindKey)
        } ?: return Result.failure()
        if (!application.stateStore.state.value.enableBroadcastControl) {
            AndroidAppLogger.info(LogTag, "Ignored $kind update because broadcast control is disabled")
            return Result.success()
        }
        AndroidAppLogger.info(LogTag, "Broadcast $kind update started")
        val checkpoint = BroadcastUpdateProgress(applicationContext, kind.name, id.toString())
        return try {
            val foreground = try {
                setForeground(getForegroundInfo())
                true
            } catch (error: IllegalStateException) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || error !is ForegroundServiceStartNotAllowedException) throw error
                AndroidAppLogger.info(LogTag, "Foreground update unavailable; continuing as checkpointed background work")
                false
            }
            // Leave time for cancellation and persistence before the ordinary Worker deadline.
            val success = withTimeoutOrNull((if (foreground) Long.MAX_VALUE else 8 * 60 * 1000L).milliseconds) { when (kind) {
                BroadcastUpdateKind.Subscription -> application.updateBroadcastSubscriptions(checkpoint)
                BroadcastUpdateKind.Resource -> {
                    val state = application.stateStore.state.value
                    val all = ResourceFileUpdateRequest.All(
                        source = state.resourceFileUpdateSource(),
                        options = state.resourceFileUpdateOptions(),
                        customResourceFiles = state.customResourceFiles,
                    )
                    for (target in all.targets) {
                        currentCoroutineContext().ensureActive()
                        val key = target.toString()
                        if (checkpoint.completed(key)) continue
                        val request = when (target) {
                            is ResourceFileUpdateTarget.BuiltIn -> ResourceFileUpdateRequest.BuiltIn(
                                target.kind, all.source, all.options, all.customResourceFiles,
                            )
                            is ResourceFileUpdateTarget.Custom -> ResourceFileUpdateRequest.Custom(
                                all.customResourceFiles.first { it.id == target.id }, all.options, all.customResourceFiles,
                            )
                        }
                        val result = if (foreground) {
                            application.resourceFileUpdateCoordinator.enqueueAndAwait(request)
                        } else {
                            // Fail an excessively slow file rather than starving later resources.
                            withTimeoutOrNull((6 * 60 * 1000L).milliseconds) {
                                application.resourceFileUpdateCoordinator.enqueueAndAwait(request)
                            }
                        }
                        AndroidAppLogger.info(LogTag, "Broadcast resource result=${result?.javaClass?.simpleName ?: "TimedOut"}")
                        if (result is ResourceFileUpdateResult.Cancelled) throw CancellationException("Resource update cancelled")
                        checkpoint.record(key, result is ResourceFileUpdateResult.Success)
                    }
                    checkpoint.succeeded()
                }
            } } ?: return Result.retry()
            checkpoint.clear()

            AndroidAppLogger.info(LogTag, "Broadcast $kind update completed: success=$success")
            if (success) Result.success() else Result.failure()
        } catch (error: CancellationException) {
            AndroidAppLogger.info(LogTag, "Broadcast $kind update cancelled")
            throw error
        } catch (error: Throwable) {
            AndroidAppLogger.error(LogTag, "Broadcast $kind update failed", error)
            Result.failure()
        } finally {
            withContext(NonCancellable) { application.stateStore.awaitPersistence() }
        }
    }
}

private const val LogTag = "BroadcastControl"

private const val ChannelId = "broadcast_updates"
