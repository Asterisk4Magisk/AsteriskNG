// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.proxy

import app.AppState
import app.ProxyServerState
import engine.root.runtime.model.RootRuntimeOwner
import engine.root.runtime.model.RootRuntimePhase
import engine.root.runtime.model.RootRuntimeSnapshot
import engine.xray.XrayStatsApiConfig
import engine.xray.xrayStatsApiTag

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
    val activeRootOwner: RootRuntimeOwner? = null,
    val rootSnapshot: RootRuntimeSnapshot? = null,
) {
    companion object {
        fun fromRootSnapshot(
            localOwner: RootRuntimeOwner,
            runMode: Int,
            snapshot: RootRuntimeSnapshot,
        ): ProxyEngineStatus = ProxyEngineStatus(
            running = snapshot.owner == localOwner && snapshot.phase == RootRuntimePhase.Running,
            runMode = runMode,
            activeRootOwner = snapshot.owner,
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
