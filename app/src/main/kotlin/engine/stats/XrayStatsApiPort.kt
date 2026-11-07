// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.stats

import app.AppState
import engine.network.isTcpPortAvailable
import engine.network.toPortOrNull
import engine.root.RootModeEngine
import engine.vpn.VpnDefaults
import kotlin.random.Random

internal const val XrayStatsApiListenAddress = "127.0.0.1"

internal fun AppState.xrayStatsApiExcludedPorts(): Set<Int> {
    return buildSet {
        add(localProxyPort.toPortOrNull() ?: VpnDefaults.LOCAL_PROXY_PORT)
        add(transparentProxyPort.toPortOrNull() ?: RootModeEngine.DefaultTproxyPort)
        add(bpf2SocksBridgePort.toPortOrNull() ?: RootModeEngine.DefaultBpf2SocksBridgePort)
        add(socks5ProxyPort.toPortOrNull() ?: RootModeEngine.DefaultTun2SocksProxyPort)
        if (enableVpnAppendHttpProxy) {
            add(VpnDefaults.VPN_APPEND_HTTP_PROXY_FALLBACK_PORT)
            add(VpnDefaults.VPN_APPEND_HTTP_PROXY_FALLBACK_PORT + 1)
        }
    }
}

/**
 * Picks a new stats API port below the kernel's default ephemeral range (32768-60999), so the
 * port is not handed out to outgoing connections before a boot-time core binds it.
 */
internal fun findAvailableXrayStatsApiPort(
    excludedPorts: Set<Int>,
): Int {
    repeat(AvailablePortAttempts) {
        val port = Random.nextInt(XrayStatsApiPortRange.first, XrayStatsApiPortRange.last + 1)
        if (port !in excludedPorts && isTcpPortAvailable(XrayStatsApiListenAddress, port)) {
            return port
        }
    }
    error("No available Xray stats API port")
}

/**
 * The stats API port is allocated once and then kept, because every published configuration
 * (the running core and the ROOT boot script) bakes it in. It is replaced only when it collides
 * with a configured port, or when [verifyAvailability] finds it taken while no runtime of this
 * app can be the one holding it. Callers whose own core may be listening on it must pass
 * `verifyAvailability = false`, otherwise the port held by that core would be replaced.
 */
internal fun resolveXrayStatsApiPort(
    preferredPort: Int?,
    excludedPorts: Set<Int>,
    verifyAvailability: Boolean,
): Int {
    val port = preferredPort?.takeIf { value ->
        value in ValidPortRange &&
            value !in excludedPorts &&
            (!verifyAvailability || isTcpPortAvailable(XrayStatsApiListenAddress, value))
    }
    return port ?: findAvailableXrayStatsApiPort(excludedPorts)
}

private val ValidPortRange = 1..65_535
private val XrayStatsApiPortRange = 20_000..32_767
private const val AvailablePortAttempts = 32
