// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.mode

import app.AppState
import app.effectiveFakeDnsEnabled
import engine.network.NetworkDefaults
import engine.proxy.LocalProxyOptions
import engine.proxy.buildLocalSocksInbound
import engine.proxy.toLocalProxyOptions
import engine.root.daemon.config.AsteriskdBpf2SocksHelper
import engine.root.daemon.config.AsteriskdConfig
import engine.root.daemon.config.AsteriskdMode
import engine.root.daemon.config.AsteriskdModeOptions
import engine.root.config.RootConfigBuildContext
import engine.root.config.RootIptablesConfig
import engine.root.config.RootModeStartConfig
import engine.root.config.RootStartConfig
import engine.root.config.bpf2SocksBridgePortValue
import engine.root.config.buildAsteriskdConfig
import engine.root.config.buildRootSharedProxyInbounds
import engine.root.config.tun2SocksInternalProxyPortValue
import engine.xray.XrayProtocols
import engine.xray.XrayTags
import engine.xray.toJsonStringArray
import engine.xray.xraySniffingDestOverrides
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val RootBpf2SocksListenAddress = NetworkDefaults.IPV4_ANY_ADDRESS
private const val RootBpf2SocksSocksInboundAddress = "127.0.0.1"

internal fun RootConfigBuildContext.buildBpf2SocksStartConfig(): RootModeStartConfig {
    val appState = this.appState
    val localProxyOptions = appState.toLocalProxyOptions()
    val socksPort = appState.tun2SocksInternalProxyPortValue()
    val rootStartConfig = buildRootStartConfig(
        inbounds = appState.buildBpf2SocksInbounds(localProxyOptions, socksPort),
        dnsHijackInboundTags = listOf(XrayTags.BPF2SOCKS_INBOUND),
    )
    val iptablesConfig = buildRootIptablesConfig(base = Bpf2SocksBasePolicyConfig)
        .copy(enableEbpfRules = true)
    return RootModeStartConfig(
        root = rootStartConfig,
        localProxyOptions = localProxyOptions,
        asteriskdConfig = rootStartConfig.buildAsteriskdConfig(
            mode = AsteriskdMode.Bpf2Socks,
            iptablesConfig = iptablesConfig,
            virtualInterfaces = emptyList(),
            modeOptions = AsteriskdModeOptions(transparentPort = null, tunnelName = null),
            helper = AsteriskdBpf2SocksHelper(
                executablePath = rootStartConfig.runtimePaths.bpf2SocksExecutablePath,
                bridgeListenAddress = RootBpf2SocksListenAddress,
                bridgePort = appState.bpf2SocksBridgePortValue(),
                socksHost = RootBpf2SocksSocksInboundAddress,
                socksPort = socksPort,
            ),
        ),
    )
}

private fun AppState.buildBpf2SocksInbounds(
    localProxyOptions: LocalProxyOptions,
    socksPort: Int,
): List<JsonObject> = buildList {
    add(buildBpf2SocksSocksInbound(this@buildBpf2SocksInbounds, socksPort))
    add(buildLocalSocksInbound(this@buildBpf2SocksInbounds, XrayTags.LOCAL_SOCKS_INBOUND, localProxyOptions))
    addAll(buildRootSharedProxyInbounds(httpInboundTag = XrayTags.BPF2SOCKS_HTTP_INBOUND))
}

private fun buildBpf2SocksSocksInbound(appState: AppState, port: Int): JsonObject = buildJsonObject {
    put("tag", XrayTags.BPF2SOCKS_INBOUND)
    put("listen", RootBpf2SocksSocksInboundAddress)
    put("port", port)
    put("protocol", XrayProtocols.SOCKS)
    put(
        "settings",
        buildJsonObject {
            put("auth", "noauth")
            put("udp", true)
            put("ip", RootBpf2SocksSocksInboundAddress)
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

private val Bpf2SocksBasePolicyConfig = RootIptablesConfig()
