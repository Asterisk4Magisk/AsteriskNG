// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.mode

import app.AppState
import app.effectiveFakeDnsEnabled
import engine.proxy.LocalProxyOptions
import engine.proxy.buildLocalSocksInbound
import engine.proxy.toLocalProxyOptions
import engine.root.config.RootConfigBuildContext
import engine.root.config.RootModeStartConfig
import engine.root.config.buildAsteriskdConfig
import engine.root.config.buildRootSharedProxyInbounds
import engine.root.config.tun2SocksInternalProxyPortValue
import engine.root.daemon.config.AsteriskdHevSocks5TunnelHelper
import engine.root.daemon.config.AsteriskdMode
import engine.root.daemon.config.AsteriskdModeOptions
import engine.vpn.toTunOptions
import engine.xray.XrayProtocols
import engine.xray.XrayTags
import engine.xray.toJsonStringArray
import engine.xray.xraySniffingDestOverrides
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun RootConfigBuildContext.buildTun2SocksStartConfig(): RootModeStartConfig {
    val appState = this.appState
    val tunOptions = appState.toTunOptions()
    val localProxyOptions = appState.toLocalProxyOptions()
    val socks5ProxyPort = appState.tun2SocksInternalProxyPortValue()
    val iptablesConfig = buildRootIptablesConfig()
    val rootStartConfig = buildRootStartConfig(
        inbounds = appState.buildTun2SocksInbounds(localProxyOptions, socks5ProxyPort),
        dnsHijackInboundTags = listOf(XrayTags.TUN2SOCKS_INBOUND),
    )
    return RootModeStartConfig(
        root = rootStartConfig,
        localProxyOptions = localProxyOptions,
        asteriskdConfig = rootStartConfig.buildAsteriskdConfig(
            mode = AsteriskdMode.Tun2Socks,
            iptablesConfig = iptablesConfig,
            virtualInterfaces = listOf("asterisk0"),
            modeOptions = AsteriskdModeOptions(
                transparentPort = null,
                tunnelName = null,
            ),
            helper = AsteriskdHevSocks5TunnelHelper(
                executablePath = rootStartConfig.runtimePaths.hevSocks5TunnelExecutablePath,
                socksHost = Tun2SocksListenAddress,
                socksPort = socks5ProxyPort,
                tunnelName = "asterisk0",
                mtu = tunOptions.mtu,
                ipv4Address = tunOptions.ipv4Address.address,
                ipv6Address = tunOptions.ipv6Address.address.takeIf { appState.enableIpv6 },
                multiQueue = true,
                tcpFastOpen = true,
            ),
        ),
    )
}

internal const val Tun2SocksListenAddress = "127.0.0.1"
internal const val DefaultTun2SocksProxyPort = 65534

private fun AppState.buildTun2SocksInbounds(
    localProxyOptions: LocalProxyOptions,
    socks5ProxyPort: Int,
): List<JsonObject> {
    return buildList {
        add(buildTun2SocksInbound(this@buildTun2SocksInbounds, socks5ProxyPort))
        add(buildLocalSocksInbound(this@buildTun2SocksInbounds, XrayTags.LOCAL_SOCKS_INBOUND, localProxyOptions))
        addAll(
            buildRootSharedProxyInbounds(
                httpInboundTag = XrayTags.TUN2SOCKS_HTTP_INBOUND,
            ),
        )
    }
}

private fun buildTun2SocksInbound(
    appState: AppState,
    port: Int,
): JsonObject {
    return buildJsonObject {
        put("tag", XrayTags.TUN2SOCKS_INBOUND)
        put("listen", Tun2SocksListenAddress)
        put("port", port)
        put("protocol", XrayProtocols.SOCKS)
        put(
            "settings",
            buildJsonObject {
                put("auth", "noauth")
                put("udp", true)
                put("ip", Tun2SocksListenAddress)
                put("userLevel", 0)
            },
        )
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
