// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.proxy

import app.AppState
import app.effectiveFakeDnsEnabled
import app.modes.RunModeBpf2Socks
import app.modes.RunModeTun2Socks
import app.modes.RunModeTproxy
import engine.network.findAvailableTcpPort
import engine.network.isTcpPortAvailable
import engine.network.NetworkDefaults
import engine.network.toPortOrNull
import engine.root.RootBpf2SocksDefaultBridgePort
import engine.tproxy.DefaultTproxyPort
import engine.tun2socks.DefaultTun2SocksProxyPort
import engine.vpn.VpnDefaults
import engine.xray.XrayProtocols
import engine.xray.toJsonStringArray
import engine.xray.xraySniffingDestOverrides
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val LocalProxyLoopbackAddress = NetworkDefaults.IPV4_LOOPBACK_ADDRESS
private const val LocalProxyAllInterfacesAddress = NetworkDefaults.IPV4_ANY_ADDRESS

internal data class LocalProxyOptions(
    val listenAddress: String,
    val port: Int,
    val username: String,
    val password: String,
)

internal object LocalProxyRuntime {
    private val currentOptions = AtomicReference<LocalProxyOptions?>()

    fun update(options: LocalProxyOptions) {
        currentOptions.set(options)
    }

    fun clear() {
        currentOptions.set(null)
    }

    fun current(): LocalProxyOptions? {
        return currentOptions.get()
    }
}

internal fun AppState.withResolvedDynamicLocalProxyPort(): AppState {
    if (!enableDynamicLocalProxyPort) return this

    val configuredPort = localProxyPort.toPortOrNull()
    val listenAddress = localProxyListenAddress()
    val excludedPorts = localProxyExcludedPorts()
    val currentOptions = LocalProxyRuntime.current()
    val canKeepConfiguredPort = configuredPort != null &&
        configuredPort !in excludedPorts &&
        (
            isPortAvailable(listenAddress, configuredPort) ||
                currentOptions?.matches(listenAddress, configuredPort) == true
            )
    val resolvedPort = when {
        canKeepConfiguredPort -> configuredPort
        else -> availablePort(listenAddress, excludedPorts) ?: configuredPort ?: VpnDefaults.LOCAL_PROXY_PORT
    }
    val resolvedPortText = resolvedPort.toString()
    return if (localProxyPort == resolvedPortText) this else copy(localProxyPort = resolvedPortText)
}

internal fun AppState.toLocalProxyOptions(): LocalProxyOptions {
    return LocalProxyOptions(
        listenAddress = localProxyListenAddress(),
        port = localProxyPort.toPortOrNull() ?: VpnDefaults.LOCAL_PROXY_PORT,
        username = localProxyUsername.trim(),
        password = localProxyPassword,
    )
}

internal fun buildLocalSocksInbound(
    appState: AppState,
    tag: String,
    options: LocalProxyOptions,
): JsonObject {
    return buildJsonObject {
        put("tag", tag)
        put("listen", options.listenAddress)
        put("port", options.port)
        put("protocol", XrayProtocols.SOCKS)
        put("settings", options.toSocksInboundSettings())
        put(
            "sniffing",
            buildJsonObject {
                put("enabled", appState.enableSniffing)
                put("destOverride", xraySniffingDestOverrides(appState.effectiveFakeDnsEnabled).toJsonStringArray())
                put("routeOnly", appState.enableSniffingRouteOnly)
            },
        )
    }
}

private fun AppState.localProxyListenAddress(): String {
    return if (localProxyListenAllInterfaces) {
        LocalProxyAllInterfacesAddress
    } else {
        LocalProxyLoopbackAddress
    }
}

private fun AppState.localProxyExcludedPorts(): Set<Int> {
    return buildSet {
        if (runMode == RunModeTproxy) {
            add(transparentProxyPort.toPortOrNull() ?: DefaultTproxyPort)
        }
        if (runMode == RunModeTun2Socks) {
            add(socks5ProxyPort.toPortOrNull() ?: DefaultTun2SocksProxyPort)
        }
        if (runMode == RunModeBpf2Socks) {
            add(bpf2SocksBridgePort.toPortOrNull() ?: RootBpf2SocksDefaultBridgePort)
            add(socks5ProxyPort.toPortOrNull() ?: DefaultTun2SocksProxyPort)
        }
        if (enableHttpProxy) {
            httpProxyPort.toPortOrNull()?.let(::add)
        }
    }
}

private fun LocalProxyOptions.matches(listenAddress: String, port: Int): Boolean {
    return this.port == port &&
        (
            this.listenAddress == listenAddress ||
                this.listenAddress == LocalProxyAllInterfacesAddress &&
                listenAddress == LocalProxyLoopbackAddress
            )
}

private fun LocalProxyOptions.toSocksInboundSettings(): JsonObject {
    return buildJsonObject {
        put("auth", if (username.isBlank()) "noauth" else "password")
        put("udp", true)
        put("userLevel", 0)
        if (listenAddress == LocalProxyLoopbackAddress) {
            put("ip", LocalProxyLoopbackAddress)
        }
        if (username.isNotBlank()) {
            put(
                "users",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("user", username)
                            put("pass", password)
                        },
                    )
                },
            )
        }
    }
}

internal fun availablePort(
    listenAddress: String,
    excludedPorts: Set<Int> = emptySet(),
): Int? {
    return findAvailableTcpPort(listenAddress, excludedPorts)
}

private fun isPortAvailable(
    listenAddress: String,
    port: Int,
): Boolean {
    return isTcpPortAvailable(listenAddress, port)
}
