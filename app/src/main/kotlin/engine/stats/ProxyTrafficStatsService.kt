// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.stats

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.R
import features.logs.AndroidAppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class ProxyTrafficStatsService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val contentIntent by lazy {
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
    private var pollingJob: Job? = null
    private var activeRuntime: ProxyTrafficStatsRuntime? = null
    private var accumulator = XrayTrafficSessionAccumulator()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ActionStop) {
            stopStats()
            return START_NOT_STICKY
        }

        val runtime = intent?.readRuntime() ?: ProxyTrafficStatsRuntimeStore.read(this)
        if (runtime == null) {
            stopStats()
            return START_NOT_STICKY
        }

        if (runtime == activeRuntime && pollingJob?.isActive == true) {
            return START_STICKY
        }
        if (runtime != activeRuntime) {
            accumulator = XrayTrafficSessionAccumulator()
        }
        activeRuntime = runtime
        startStats(runtime)
        return START_STICKY
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        pollingJob = null
        activeRuntime = null
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun startStats(runtime: ProxyTrafficStatsRuntime) {
        pollingJob?.cancel()
        val initialNotification = buildNotification(
            runtime = runtime,
            sample = EmptyTrafficSample,
        )
        val foregroundStarted = runCatching {
            startForegroundCompat(initialNotification)
        }.onFailure { error ->
            AndroidAppLogger.warn(LogTag, "Failed to start traffic stats foreground service", error)
            stopStats()
        }.isSuccess
        if (!foregroundStarted) return

        val sessionAccumulator = accumulator
        pollingJob = serviceScope.launch {
            var lastPollAt = SystemClock.elapsedRealtime()
            var failures = 0
            XrayStatsClient(
                listenAddress = runtime.listenAddress,
                port = runtime.port,
            ).use { client ->
                while (isActive) {
                    delay(PollIntervalMillis.milliseconds)
                    val now = SystemClock.elapsedRealtime()
                    val elapsedMillis = now - lastPollAt
                    lastPollAt = now
                    runCatching {
                        client.queryOutboundTraffic(reset = true)
                    }.onSuccess { delta ->
                        failures = 0
                        notificationManager.notify(
                            NotificationId,
                            buildNotification(
                                runtime = runtime,
                                sample = sessionAccumulator.record(delta, elapsedMillis),
                            ),
                        )
                    }.onFailure { error ->
                        failures += 1
                        AndroidAppLogger.warn(LogTag, "Failed to query Xray traffic stats", error)
                        if (failures >= MaxConsecutiveFailures) {
                            AndroidAppLogger.warn(LogTag, "Stopping traffic stats notification after repeated failures")
                            stopStats()
                        }
                    }
                }
            }
        }
    }

    private fun stopStats() {
        pollingJob?.cancel()
        pollingJob = null
        activeRuntime = null
        accumulator = XrayTrafficSessionAccumulator()
        runCatching {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        notificationManager.cancel(NotificationId)
        stopSelf()
    }

    private fun buildNotification(
        runtime: ProxyTrafficStatsRuntime,
        sample: XrayTrafficSessionSample,
    ): Notification {
        val speedText = getString(
            R.string.proxy_traffic_stats_notification_speed,
            sample.speedBytesPerSecond.uplink.toTrafficSpeedString(),
            sample.speedBytesPerSecond.downlink.toTrafficSpeedString(),
        )
        val totalText = getString(
            R.string.proxy_traffic_stats_notification_total,
            sample.totalBytes.uplink.toTrafficSizeString(),
            sample.totalBytes.downlink.toTrafficSizeString(),
        )
        return notificationBuilder()
            .setSmallIcon(R.drawable.ic_qs_proxy)
            .setContentTitle(runtime.serverName.ifBlank { getString(R.string.app_name) })
            .setContentText(speedText)
            .setStyle(Notification.BigTextStyle().bigText("$speedText\n$totalText"))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun notificationBuilder(): Notification.Builder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, ChannelId)
        } else {
            Notification.Builder(this)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                ChannelId,
                getString(R.string.proxy_traffic_stats_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NotificationId, notification)
        }
    }

    private fun Intent.readRuntime(): ProxyTrafficStatsRuntime? {
        val listenAddress = getStringExtra(ExtraListenAddress)?.takeIf(String::isNotBlank) ?: return null
        val port = getIntExtra(ExtraPort, 0).takeIf { value -> value > 0 } ?: return null
        val serverName = getStringExtra(ExtraServerName).orEmpty()
        return ProxyTrafficStatsRuntime(
            listenAddress = listenAddress,
            port = port,
            serverName = serverName,
        )
    }

    companion object {
        internal fun reconcile(context: Context, runtime: ProxyTrafficStatsRuntime?) {
            if (runtime == null) {
                stop(context)
            } else {
                start(context, runtime)
            }
        }

        internal fun start(
            context: Context,
            runtime: ProxyTrafficStatsRuntime,
        ) {
            val appContext = context.applicationContext
            ProxyTrafficStatsRuntimeStore.write(appContext, runtime)
            runCatching {
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(appContext, ProxyTrafficStatsService::class.java)
                        .setAction(ActionStart)
                        .putExtra(ExtraListenAddress, runtime.listenAddress)
                        .putExtra(ExtraPort, runtime.port)
                        .putExtra(ExtraServerName, runtime.serverName),
                )
            }.onFailure { error ->
                AndroidAppLogger.warn(LogTag, "Failed to request traffic stats foreground service start", error)
            }
        }

        internal fun stop(context: Context) {
            val appContext = context.applicationContext
            appContext.stopService(Intent(appContext, ProxyTrafficStatsService::class.java))
        }
    }
}

private const val LogTag = "ProxyTrafficStats"
private const val ActionStart = "app.action.START_PROXY_TRAFFIC_STATS"
private const val ActionStop = "app.action.STOP_PROXY_TRAFFIC_STATS"
private const val ExtraListenAddress = "listen_address"
private const val ExtraPort = "port"
private const val ExtraServerName = "server_name"
private const val ChannelId = "proxy_traffic_stats"
private const val NotificationId = 3001
private const val PollIntervalMillis = 1_000L
private const val MaxConsecutiveFailures = 5
private val EmptyTrafficSample = XrayTrafficSessionSample(
    speedBytesPerSecond = XrayTrafficBytes(),
    totalBytes = XrayTrafficBytes(),
)
