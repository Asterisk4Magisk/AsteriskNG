// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.proxy

import app.AppState
import app.ProxyServerState
import engine.xray.XrayStatsApiConfig

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
)

internal fun ProxyEngineStartRequest.xrayStatsApiConfig(): XrayStatsApiConfig? {
    val listenAddress = xrayStatsApiListenAddress?.takeIf(String::isNotBlank) ?: return null
    val port = xrayStatsApiPort?.takeIf { value -> value > 0 } ?: return null
    return XrayStatsApiConfig(
        listenAddress = listenAddress,
        port = port,
    )
}
