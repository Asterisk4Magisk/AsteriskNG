// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import app.AppState
import app.effectiveFakeDnsEnabled
import engine.xray.XrayProtocols
import engine.xray.XrayTags
import engine.xray.toJsonStringArray
import engine.xray.xraySniffingDestOverrides
import engine.network.NetworkDefaults
import engine.network.NetworkLimits
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

internal const val LocalProxyLoopbackAddress = NetworkDefaults.IPV4_LOOPBACK_ADDRESS
private const val LocalProxyAllInterfacesAddress = NetworkDefaults.IPV4_ANY_ADDRESS

internal data class VpnLocalProxyOptions(
    val listenAddress: String,
    val port: Int,
    val username: String,
    val password: String,
)

internal data class VpnAppendHttpProxyOptions(
    val enabled: Boolean,
    val port: Int,
) {
    companion object {
        val Disabled = VpnAppendHttpProxyOptions(
            enabled = false,
            port = 0,
        )
    }
}

internal object VpnLocalProxyRuntime {
    private val currentOptions = AtomicReference<VpnLocalProxyOptions?>()

    fun update(options: VpnLocalProxyOptions) {
        currentOptions.set(options)
    }

    fun clear() {
        currentOptions.set(null)
    }

    fun current(): VpnLocalProxyOptions? {
        return currentOptions.get()
    }
}

internal fun AppState.toVpnLocalProxyOptions(): VpnLocalProxyOptions {
    val configuredPort = localProxyPort.toIntOrNull()
        ?.takeIf { it in NetworkLimits.PORT_MIN..NetworkLimits.PORT_MAX }
        ?: VpnDefaults.LOCAL_PROXY_PORT
    val listenAddress = if (localProxyListenAllInterfaces) {
        LocalProxyAllInterfacesAddress
    } else {
        LocalProxyLoopbackAddress
    }
    return VpnLocalProxyOptions(
        listenAddress = listenAddress,
        port = if (enableDynamicLocalProxyPort) availablePort(listenAddress) ?: configuredPort else configuredPort,
        username = localProxyUsername.trim(),
        password = localProxyPassword,
    )
}

internal fun AppState.toVpnAppendHttpProxyOptions(localProxyOptions: VpnLocalProxyOptions): VpnAppendHttpProxyOptions {
    if (!enableVpnAppendHttpProxy) {
        return VpnAppendHttpProxyOptions.Disabled
    }
    return VpnAppendHttpProxyOptions(
        enabled = true,
        port = availablePort(
            listenAddress = LocalProxyLoopbackAddress,
            excludedPorts = setOf(localProxyOptions.port),
        ) ?: fallbackAppendHttpProxyPort(localProxyOptions.port),
    )
}

internal fun buildVpnLocalSocksInbound(
    appState: AppState,
    options: VpnLocalProxyOptions,
): JSONObject {
    return JSONObject()
        .put("tag", XrayTags.LOCAL_SOCKS_INBOUND)
        .put("listen", options.listenAddress)
        .put("port", options.port)
        .put("protocol", XrayProtocols.SOCKS)
        .put("settings", options.toSocksInboundSettings())
        .put(
            "sniffing",
            JSONObject()
                .put("enabled", appState.enableSniffing)
                .put("destOverride", xraySniffingDestOverrides(appState.effectiveFakeDnsEnabled).toJsonStringArray())
                .put("routeOnly", appState.enableSniffingRouteOnly),
        )
}

internal fun buildVpnAppendHttpInbound(options: VpnAppendHttpProxyOptions): JSONObject {
    return JSONObject()
        .put("tag", XrayTags.VPN_APPEND_HTTP_INBOUND)
        .put("listen", LocalProxyLoopbackAddress)
        .put("port", options.port)
        .put("protocol", XrayProtocols.HTTP)
        .put(
            "settings",
            JSONObject()
                .put("allowTransparent", false)
                .put("userLevel", 0),
        )
}

private fun VpnLocalProxyOptions.toSocksInboundSettings(): JSONObject {
    return JSONObject()
        .put("auth", if (username.isBlank()) "noauth" else "password")
        .put("udp", true)
        .put("userLevel", 0)
        .apply {
            if (listenAddress == LocalProxyLoopbackAddress) {
                put("ip", LocalProxyLoopbackAddress)
            }
            if (username.isNotBlank()) {
                put(
                    "users",
                    JSONArray().put(
                        JSONObject()
                            .put("user", username)
                            .put("pass", password),
                    ),
                )
            }
        }
}

private fun availablePort(
    listenAddress: String,
    excludedPorts: Set<Int> = emptySet(),
): Int? {
    return runCatching {
        repeat(10) {
            ServerSocket(0, 0, InetAddress.getByName(listenAddress)).use { socket ->
                if (socket.localPort !in excludedPorts) {
                    return@runCatching socket.localPort
                }
            }
        }
        null
    }.getOrNull()
}

private fun fallbackAppendHttpProxyPort(localProxyPort: Int): Int {
    return if (VpnDefaults.VPN_APPEND_HTTP_PROXY_FALLBACK_PORT == localProxyPort) {
        VpnDefaults.VPN_APPEND_HTTP_PROXY_FALLBACK_PORT + 1
    } else {
        VpnDefaults.VPN_APPEND_HTTP_PROXY_FALLBACK_PORT
    }
}
