// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import app.modes.RunModeVpnService
import app.R
import engine.proxy.mode.AndroidModeProxyEngine
import engine.proxy.ProxyEngineStartRequest
import engine.proxy.ProxyEngineStatus
import engine.xray.logDirectoryPath
import features.logs.AndroidAppLogger
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
        // Without a session there is nothing to stop, and asking would launch the service process.
        if (runtimeRunning()) AsteriskVpnService.stop(context)
        return status()
    }

    override suspend fun status(): ProxyEngineStatus = withContext(Dispatchers.IO) {
        val running = runtimeRunning()
        // The local proxy is read from the session on demand (LocalProxyRuntime), and the
        // watch reports the end of the tunnel, however it ends, to this process.
        if (running) VpnSession.watch(context)
        ProxyEngineStatus(
            running = running,
            runMode = runMode,
        )
    }

    private companion object {
        const val LogTag = "VpnXrayEngine"
    }
}

internal fun shouldLaunchVpnService(isRunning: Boolean, explicitRestart: Boolean): Boolean {
    return explicitRestart || !isRunning
}
