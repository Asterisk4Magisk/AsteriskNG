// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.stats

import app.AppState
import engine.network.findAvailableTcpPort
import engine.network.isTcpPortAvailable
import engine.network.toPortOrNull
import engine.tproxy.DefaultTproxyPort
import engine.tun2socks.DefaultTun2SocksProxyPort
import engine.vpn.VpnDefaults

internal const val XrayStatsApiListenAddress = "127.0.0.1"

internal fun AppState.xrayStatsApiExcludedPorts(): Set<Int> {
    return buildSet {
        add(localProxyPort.toPortOrNull() ?: VpnDefaults.LOCAL_PROXY_PORT)
        add(transparentProxyPort.toPortOrNull() ?: DefaultTproxyPort)
        add(socks5ProxyPort.toPortOrNull() ?: DefaultTun2SocksProxyPort)
        if (enableHttpProxy) {
            httpProxyPort.toPortOrNull()?.let(::add)
        }
        if (enableVpnAppendHttpProxy) {
            add(VpnDefaults.VPN_APPEND_HTTP_PROXY_FALLBACK_PORT)
            add(VpnDefaults.VPN_APPEND_HTTP_PROXY_FALLBACK_PORT + 1)
        }
    }
}

internal fun findAvailableXrayStatsApiPort(
    excludedPorts: Set<Int>,
): Int {
    return findAvailableTcpPort(
        listenAddress = XrayStatsApiListenAddress,
        excludedPorts = excludedPorts,
        attempts = AvailablePortAttempts,
    ) ?: error("No available Xray stats API port")
}

internal fun resolveXrayStatsApiPort(
    preferredPort: Int?,
    excludedPorts: Set<Int>,
): Int {
    val port = preferredPort?.takeIf { value ->
            value > 0 &&
            value !in excludedPorts &&
            isTcpPortAvailable(XrayStatsApiListenAddress, value)
    }
    return port ?: findAvailableXrayStatsApiPort(excludedPorts)
}

private const val AvailablePortAttempts = 32
