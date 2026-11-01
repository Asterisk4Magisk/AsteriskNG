// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import app.modes.RunModeVpnService
import app.R
import engine.proxy.mode.AndroidModeProxyEngine
import engine.proxy.LocalProxyOptions
import engine.proxy.LocalProxyRuntime
import engine.proxy.ProxyEngineStartRequest
import engine.proxy.ProxyEngineStatus
import engine.xray.logDirectoryPath
import engine.xray.prepareXrayCoreLogPaths
import engine.xray.startCoreLogTailers
import features.logs.AndroidAppLogger
import features.logs.CoreLogFileTailer
import features.logs.clearVpnServiceLogsAsApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class VpnXrayEngine(
    private val context: Context,
    private val requestVpnPermission: suspend (Intent) -> Boolean,
    private val runtimeRunning: () -> Boolean = { AsteriskVpnService.isRunning(context) },
) : AndroidModeProxyEngine {
    override val runMode: Int = RunModeVpnService

    /** The local proxy this engine published for this process, so it never clears a ROOT one. */
    @Volatile
    private var publishedLocalProxy: LocalProxyOptions? = null

    private val tailerLock = Any()
    private var logFileTailers: List<CoreLogFileTailer> = emptyList()

    override suspend fun start(request: ProxyEngineStartRequest): ProxyEngineStatus {
        return startWithPolicy(request, explicitRestart = false)
    }

    suspend fun restart(request: ProxyEngineStartRequest): ProxyEngineStatus {
        return startWithPolicy(request, explicitRestart = true)
    }

    private suspend fun startWithPolicy(
        request: ProxyEngineStartRequest,
        explicitRestart: Boolean,
    ): ProxyEngineStatus {
        if (!shouldLaunchVpnService(runtimeRunning(), explicitRestart)) return status()
        launch(request)
        return status()
    }

    private suspend fun launch(request: ProxyEngineStartRequest) {
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null && !requestVpnPermission(prepareIntent)) {
            error(context.getString(R.string.error_vpn_permission_denied))
        }
        try {
            val config = VpnXrayConfigFactory.create(context, request)
            // The core log files and their in-memory copies belong to this process; the service
            // process only hosts the core, so the previous session's logs are cleared here.
            stopLogFileTailers()
            clearVpnServiceLogsAsApp(File(config.coreLogPaths.logDirectoryPath()), LogTag)
            AsteriskVpnService.start(
                context = context,
                config = config,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // The service may run in another process and does not write this process' log.
            AndroidAppLogger.error(LogTag, "Failed to start VPN Service", error)
            throw error
        }
    }

    override suspend fun stop(): ProxyEngineStatus {
        AsteriskVpnService.stop(context)
        return status()
    }

    override suspend fun status(): ProxyEngineStatus = withContext(Dispatchers.IO) {
        val running = runtimeRunning()
        syncLocalProxy(running)
        syncLogFileTailers(running)
        ProxyEngineStatus(
            running = running,
            runMode = runMode,
        )
    }

    /**
     * Mirrors the tunnel's local proxy into this process, which performs its own HTTP fetches
     * through it. The service publishes it with the session; after a crash only the value this
     * engine published is withdrawn.
     */
    private fun syncLocalProxy(running: Boolean) {
        val published = publishedLocalProxy
        if (!running) {
            if (published != null && LocalProxyRuntime.current() == published) LocalProxyRuntime.clear()
            publishedLocalProxy = null
            return
        }
        val options = VpnSession.localProxyOptions(context)
        if (options != null) {
            LocalProxyRuntime.update(options)
        } else if (published != null && LocalProxyRuntime.current() == published) {
            LocalProxyRuntime.clear()
        }
        publishedLocalProxy = options
    }

    /**
     * Follows the core's log files into this process' log repositories while the tunnel runs.
     * The core writes the files from the service process; earlier lines are restored by the
     * repositories themselves, so tailing starts at the current end of each file.
     */
    private fun syncLogFileTailers(running: Boolean) {
        if (!running) {
            stopLogFileTailers()
            return
        }
        synchronized(tailerLock) {
            if (logFileTailers.isNotEmpty()) return
            logFileTailers = context.prepareXrayCoreLogPaths()
                .startCoreLogTailers(VpnSession.accessLogEnabled(context))
        }
    }

    private fun stopLogFileTailers() {
        synchronized(tailerLock) {
            logFileTailers.forEach(CoreLogFileTailer::stop)
            logFileTailers = emptyList()
        }
    }

    private companion object {
        const val LogTag = "VpnXrayEngine"
    }
}

internal fun shouldLaunchVpnService(isRunning: Boolean, explicitRestart: Boolean): Boolean {
    return explicitRestart || !isRunning
}
