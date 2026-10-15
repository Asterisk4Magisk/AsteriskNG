// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.proxy

import android.content.Intent
import app.AppState
import app.ProxyServerState
import engine.root.runtime.model.RootRuntimeOwner
import engine.root.runtime.model.RootRuntimeSnapshot
import engine.xray.XrayStatsApiConfig
import engine.xray.xrayStatsApiTag

/** Asks the user for VPN consent; returns whether it was granted. */
typealias VpnPermissionRequester = suspend (Intent) -> Boolean

/** Used by entry points that cannot show the system VPN consent dialog. */
val DenyVpnPermission: VpnPermissionRequester = { false }

data class ProxyEngineStartRequest(
    val appState: AppState,
    val selectedServer: ProxyServerState,
    val xrayStatsApiListenAddress: String? = null,
    val xrayStatsApiPort: Int? = null,
)

data class ProxyEngineStatus(
    val running: Boolean,
    val runMode: Int? = null,
    val appState: AppState? = null,
    val rootSnapshot: RootRuntimeSnapshot? = null,
) {
    companion object {
        fun fromRootSnapshot(
            localOwner: RootRuntimeOwner,
            runMode: Int,
            snapshot: RootRuntimeSnapshot,
        ): ProxyEngineStatus = ProxyEngineStatus(
            running = snapshot.owner == localOwner && snapshot.running,
            runMode = runMode,
            rootSnapshot = snapshot,
        )
    }
}

internal fun ProxyEngineStartRequest.xrayStatsApiConfig(): XrayStatsApiConfig? {
    val listenAddress = xrayStatsApiListenAddress?.takeIf(String::isNotBlank) ?: return null
    val port = xrayStatsApiPort?.takeIf { value -> value > 0 } ?: return null
    return XrayStatsApiConfig(
        listenAddress = listenAddress,
        port = port,
        apiTag = selectedServer.server.xrayStatsApiTag(),
    )
}
