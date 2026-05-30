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

internal class VpnXrayEngine(
    private val context: Context,
    private val requestVpnPermission: suspend (Intent) -> Boolean,
) : AndroidModeProxyEngine {
    override val runMode: Int = RunModeVpnService

    override suspend fun start(request: ProxyEngineStartRequest): ProxyEngineStatus {
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null && !requestVpnPermission(prepareIntent)) {
            error(context.getString(R.string.error_vpn_permission_denied))
        }
        AsteriskVpnService.start(
            context = context,
            config = VpnXrayConfigFactory.create(context, request),
        )
        return status()
    }

    override suspend fun stop(): ProxyEngineStatus {
        AsteriskVpnService.stop(context)
        return status()
    }

    override suspend fun status(): ProxyEngineStatus {
        return ProxyEngineStatus(running = AsteriskVpnService.isRunning(), runMode = runMode)
    }
}
