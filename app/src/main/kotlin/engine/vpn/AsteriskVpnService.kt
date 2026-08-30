// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import app.R
import app.modes.ProxyAppListModeBlacklist
import app.modes.ProxyAppListModeGlobal
import app.modes.ProxyAppListModeWhitelist
import engine.network.NetworkDefaults
import engine.proxy.LocalProxyLoopbackAddress
import engine.proxy.LocalProxyRuntime
import engine.vpn.hevtun.HevTunRuntime
import engine.xray.logDirectoryPath
import engine.xray.startCoreLogTailers
import features.logs.AndroidAppLogger
import features.logs.CoreLogFileTailer
import features.logs.clearServiceLogsAsApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        }
        return START_NOT_STICKY
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
        stopVpn()
        clearServiceLogsAsApp(File(config.coreLogPaths.logDirectoryPath()), LogTag)
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

    private fun stopVpn() {
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
