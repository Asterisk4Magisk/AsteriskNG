// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import app.R
import app.AsteriskApplication
import app.modes.ProxyAppListModeBlacklist
import app.modes.ProxyAppListModeGlobal
import app.modes.ProxyAppListModeWhitelist
import app.modes.RunModeVpnService
import androidx.core.app.NotificationCompat
import data.AppSettingsPreferences
import engine.proxy.DenyVpnPermission
import engine.proxy.ProxyEngineStartRequest
import engine.network.NetworkDefaults
import engine.proxy.LocalProxyLoopbackAddress
import engine.proxy.LocalProxyRuntime
import engine.vpn.hevtun.HevTunRuntime
import engine.xray.logDirectoryPath
import engine.xray.startCoreLogTailers
import features.logs.AndroidAppLogger
import features.logs.CoreLogFileTailer
import features.logs.clearVpnServiceLogsAsApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import system.AndroidLauncherIcon
import system.getInstalledApplicationsCompat
import utils.toTrimmedNonEmptyDistinctList
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

@SuppressLint("VpnServicePolicy")
class AsteriskVpnService : VpnService() {
    private var tunFileDescriptor: ParcelFileDescriptor? = null
    private var logFileTailers: List<CoreLogFileTailer> = emptyList()
    private var hevTunRuntime: HevTunRuntime? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val operationMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var foregroundStarted = false

    @Volatile
    private var latestStartId = 0
    private val sessionPreferences by lazy { AppSettingsPreferences(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            AsteriskVpnServiceIntents.ACTION_STOP -> {
                serviceScope.launch {
                    try {
                        operationMutex.withLock {
                            stopVpn()
                        }
                    } finally {
                        stopSelfOnMain(startId)
                    }
                }
            }

            AsteriskVpnServiceIntents.ACTION_START -> {
                val config = intent.readVpnServiceStartConfig()
                if (config == null) {
                    completeStart(Result.failure(IllegalStateException(getString(R.string.error_vpn_start_config_missing))))
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                serviceScope.launch {
                    operationMutex.withLock {
                        runCatching {
                            startVpn(config)
                        }.onSuccess {
                            sessionPreferences.setVpnSessionActive(true)
                            completeStart(Result.success(Unit))
                        }.onFailure { error ->
                            AndroidAppLogger.error(LogTag, "Failed to start VPN Service", error)
                            stopVpn()
                            completeStart(Result.failure(error))
                            stopSelfOnMain(startId)
                        }
                    }
                }
            }

            // The boot receiver and the system's always-on VPN start carry no configuration.
            AsteriskVpnServiceIntents.ACTION_RESUME,
            VpnService.SERVICE_INTERFACE,
            -> resumeVpn(startId)
        }
        return START_NOT_STICKY
    }

    /**
     * Starts the tunnel from the persisted state when the caller only names the service: the
     * boot receiver's resume request and the system's always-on VPN start.
     *
     * The service must already be a foreground service before it spends time compiling the
     * configuration: a `startForegroundService()` caller has five seconds to reach
     * `startForeground()`, and a background start would otherwise be stopped while it compiles.
     * The actual start is delegated to the shared proxy engine, which resolves the dynamic
     * ports and compiles the configuration exactly as a manual start does. Once the tunnel is
     * up, the system's binding to the VPN service keeps the process alive just as after a
     * manual start, so the start-up notification is removed again.
     */
    private fun resumeVpn(startId: Int) {
        val asteriskApplication = application as? AsteriskApplication
        if (asteriskApplication == null) {
            AndroidAppLogger.error(LogTag, "Cannot resume VPN Service without the Asterisk application")
            stopSelfOnMain(startId)
            return
        }
        // Without consent the foreground-service type is not allowed and the tunnel would fail.
        if (VpnService.prepare(this) != null) {
            AndroidAppLogger.warn(LogTag, "Refused to resume VPN Service without VPN permission")
            stopSelfOnMain(startId)
            return
        }
        promoteToForeground()
        if (!foregroundStarted) {
            // The foreground-service type is not allowed for this app right now; a background
            // start would be killed by the system anyway, so fail cleanly.
            stopSelfOnMain(startId)
            return
        }
        serviceScope.launch {
            val result = runCatching {
                val state = asteriskApplication.stateStore.state.value
                check(state.runMode == RunModeVpnService) {
                    "VPN Service is not the active run mode"
                }
                val server = checkNotNull(
                    state.proxyServers.firstOrNull { server -> server.id == state.selectedProxyServerId },
                ) {
                    getString(R.string.proxy_server_list_select_first)
                }
                asteriskApplication.proxyEngine.start(
                    ProxyEngineStartRequest(state, server),
                    DenyVpnPermission,
                )
            }
            val status = result.getOrNull()
            if (status?.running == true) {
                asteriskApplication.stateStore.update { current ->
                    current.copy(
                        proxyRunning = true,
                        localProxyPort = status.appState?.localProxyPort ?: current.localProxyPort,
                    )
                }
                stopForegroundNotification()
                return@launch
            }
            AndroidAppLogger.error(
                LogTag,
                "Failed to resume VPN Service",
                result.exceptionOrNull() ?: IllegalStateException("VPN Service did not start"),
            )
            operationMutex.withLock {
                // Another start may own the service by now; leave its tunnel alone.
                if (running || pendingStart != null) {
                    stopForegroundNotification()
                    return@withLock
                }
                stopVpn()
                asteriskApplication.stateStore.update { current -> current.copy(proxyRunning = false) }
                // The engine's own start used a newer start id; stopSelf(id) only stops the
                // service when no start arrived after the one it names.
                stopSelfOnMain(latestStartId)
            }
        }
    }

    private fun promoteToForeground(): Boolean {
        if (foregroundStarted) return true
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                ForegroundChannelId,
                getString(R.string.settings_run_mode_vpn_service),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = NotificationCompat.Builder(this, ForegroundChannelId)
            .setSmallIcon(R.drawable.ic_qs_proxy)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.quick_settings_tile_running))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    AndroidLauncherIcon.launchIntent(this),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val promoted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    ForegroundNotificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
                )
            } else {
                startForeground(ForegroundNotificationId, notification)
            }
        }.onFailure { error ->
            AndroidAppLogger.error(LogTag, "Failed to promote VPN Service to the foreground", error)
        }.isSuccess
        if (promoted) foregroundStarted = true
        return promoted
    }

    private fun stopForegroundNotification() {
        if (!foregroundStarted) return
        foregroundStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        runCatching {
            stopVpn()
        }.onFailure { error ->
            AndroidAppLogger.warn(LogTag, "Failed to stop VPN Service while destroying service", error)
        }
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        runCatching {
            stopVpn()
        }.onFailure { error ->
            AndroidAppLogger.warn(LogTag, "Failed to stop VPN Service while revoking", error)
        }
        super.onRevoke()
    }

    private fun stopSelfOnMain(startId: Int) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopSelf(startId)
        } else {
            mainHandler.post { stopSelf(startId) }
        }
    }

    private fun startVpn(config: VpnServiceStartConfig) {
        // Keep the foreground promotion from resumeVpn() while clearing a previous session.
        stopVpn(removeForeground = false)
        clearVpnServiceLogsAsApp(File(config.coreLogPaths.logDirectoryPath()), LogTag)
        logFileTailers = config.coreLogPaths.startCoreLogTailers(config.enableAccessLog)
        tunFileDescriptor = establishTun(config)
        val tunFd = tunFileDescriptor?.fd ?: error(getString(R.string.error_vpn_tun_fd_unavailable))
        AndroidLibXrayLiteRuntime.start(
            context = this,
            config = config,
            tunFd = config.xrayTunFd(tunFd),
        )
        config.hevSocks5TunnelConfig?.let { hevConfig ->
            val runtime = hevTunRuntime ?: HevTunRuntime().also { hevTunRuntime = it }
            runtime.start(hevConfig, tunFd)
            AndroidAppLogger.info(LogTag, "Started Hev TUN with VPN file descriptor")
        }
        LocalProxyRuntime.update(config.localProxyOptions)
        running = true
    }

    private fun establishTun(config: VpnServiceStartConfig): ParcelFileDescriptor {
        val builder = Builder()
            .setSession(config.sessionName)
            .setMtu(config.mtu)
            .addAddress(config.ipv4Address, config.ipv4PrefixLength)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        if (config.enableIpv6 && config.ipv6Address != null) {
            builder
                .addAddress(config.ipv6Address, config.ipv6PrefixLength)
        }

        builder.applyVpnRoutes(config)

        if (config.enableLocalDns) {
            config.dnsServers.forEach { dnsServer ->
                builder.addDnsServer(dnsServer)
            }
        }

        builder.applyApplicationPolicy(config)
        builder.applyAppendHttpProxy(config)

        return builder.establish() ?: error(getString(R.string.error_vpn_tunnel_establish_failed))
    }

    private fun Builder.applyVpnRoutes(config: VpnServiceStartConfig): Builder {
        addRoute(NetworkDefaults.IPV4_ANY_ADDRESS, 0)
        if (config.enableIpv6 && config.ipv6Address != null) {
            addRoute(NetworkDefaults.IPV6_ANY_ADDRESS, 0)
        }
        return this
    }

    private fun Builder.applyApplicationPolicy(config: VpnServiceStartConfig): Builder {
        val policy = config.applicationPolicy
        val selfPackageName = packageName
        when (policy.mode) {
            ProxyAppListModeWhitelist -> {
                val allowedCount = addAllowedApplications(policy.packageNames.filterNot { it.trim() == selfPackageName })
                if (allowedCount == 0) {
                    // An empty allowed list means "all apps" to Android, so use a full deny list instead.
                    addDisallowedApplications(installedPackageNames())
                }
            }

            ProxyAppListModeBlacklist -> {
                addDisallowedApplications(policy.packageNames + selfPackageName)
            }

            ProxyAppListModeGlobal -> {
                addDisallowedApplications(listOf(selfPackageName))
            }

            else -> Unit
        }
        AndroidAppLogger.info(LogTag, "Excluded self package from VPN routing: $selfPackageName")
        return this
    }

    private fun Builder.applyAppendHttpProxy(config: VpnServiceStartConfig): Builder {
        if (config.appendHttpProxyOptions.enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setHttpProxy(ProxyInfo.buildDirectProxy(LocalProxyLoopbackAddress, config.appendHttpProxyOptions.port))
            AndroidAppLogger.info(
                LogTag,
                "Appended VPN HTTP proxy: $LocalProxyLoopbackAddress:${config.appendHttpProxyOptions.port}",
            )
        }
        return this
    }

    private fun Builder.addAllowedApplications(packageNames: List<String>): Int {
        return packageNames.toTrimmedNonEmptyDistinctList().count { packageName ->
            addApplicationIfInstalled(packageName) {
                addAllowedApplication(packageName)
            }
        }
    }

    private fun Builder.addDisallowedApplications(packageNames: List<String>): Int {
        return packageNames.toTrimmedNonEmptyDistinctList().count { packageName ->
            addApplicationIfInstalled(packageName) {
                addDisallowedApplication(packageName)
            }
        }
    }

    private fun addApplicationIfInstalled(packageName: String, addApplication: () -> Unit): Boolean {
        return runCatching {
            addApplication()
        }.fold(
            onSuccess = { true },
            onFailure = { error ->
                if (error is PackageManager.NameNotFoundException) {
                    false
                } else {
                    throw error
                }
            },
        )
    }

    private fun installedPackageNames(): List<String> {
        return packageManager.getInstalledApplicationsCompat()
            .map { applicationInfo -> applicationInfo.packageName }
            .distinct()
    }

    private fun stopVpn(removeForeground: Boolean = true) {
        logFileTailers.forEach { tailer -> tailer.stop() }
        logFileTailers = emptyList()
        stopNativeRuntimesBounded()
        runCatching {
            tunFileDescriptor?.close()
        }.onFailure { error ->
            AndroidAppLogger.warn(LogTag, "Failed to close VPN TUN file descriptor", error)
        }
        tunFileDescriptor = null
        LocalProxyRuntime.clear()
        running = false
        sessionPreferences.setVpnSessionActive(false)
        if (removeForeground) stopForegroundNotification()
    }

    private fun stopNativeRuntimesBounded() {
        val tasks = buildList {
            add("Hev TUN" to { hevTunRuntime?.stop() })
            add("AndroidLibXrayLite" to { AndroidLibXrayLiteRuntime.stop() })
        }
        val completion = CountDownLatch(tasks.size)
        val threads = tasks.map { (name, action) ->
            Thread({
                runCatching {
                    action()
                }.onFailure { error ->
                    AndroidAppLogger.warn(LogTag, "Failed to stop $name while stopping VPN Service", error)
                }.also {
                    completion.countDown()
                }
            }, "$LogTag-$name").apply {
                isDaemon = true
            }.also { thread ->
                thread.start()
            }
        }

        if (!completion.await(RuntimeShutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
            threads.filter(Thread::isAlive).forEach { thread ->
                thread.interrupt()
                AndroidAppLogger.warn(
                    LogTag,
                    "Timed out stopping VPN runtime after ${RuntimeShutdownTimeoutMillis}ms: ${thread.name}",
                )
            }
        }
    }

    companion object {
        private const val LogTag = "AsteriskVpnService"
        private const val RuntimeShutdownTimeoutMillis = 1_000L
        private const val ForegroundChannelId = "vpn-service"
        private const val ForegroundNotificationId = 4

        @Volatile
        private var running = false

        @Volatile
        private var pendingStart: CompletableDeferred<Result<Unit>>? = null

        internal suspend fun start(context: Context, config: VpnServiceStartConfig) {
            val result = CompletableDeferred<Result<Unit>>()
            pendingStart = result
            try {
                context.startService(AsteriskVpnServiceIntents.startIntent(context, config))
                withTimeout(10_000.milliseconds) {
                    result.await()
                }.getOrThrow()
            } finally {
                if (pendingStart === result) {
                    pendingStart = null
                }
            }
        }

        internal fun stop(context: Context) {
            running = false
            context.startService(AsteriskVpnServiceIntents.stopIntent(context))
        }

        internal fun isRunning(): Boolean {
            return running && AndroidLibXrayLiteRuntime.isRunning()
        }

        private fun completeStart(result: Result<Unit>) {
            pendingStart?.complete(result)
            pendingStart = null
        }

    }
}
