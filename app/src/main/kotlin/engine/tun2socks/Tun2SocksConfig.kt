// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.tun2socks

import android.content.Context
import app.AppState
import app.effectiveFakeDnsEnabled
import engine.hevtun.HevSocks5TunnelConfig
import engine.hevtun.HevSocks5TunnelConfigFileName
import engine.hevtun.HevSocks5TunnelLogFileName
import engine.hevtun.HevSocks5TunnelPidFileName
import engine.hevtun.hevSocks5TunnelLogFile
import engine.proxy.LocalProxyOptions
import engine.proxy.buildLocalSocksInbound
import engine.proxy.toLocalProxyOptions
import engine.root.AsteriskdConfig
import engine.root.AsteriskdBypassConsumerChains
import engine.root.AsteriskdMode
import engine.root.RootConfigBuildContext
import engine.root.RootEbpfRuntimeConfig
import engine.root.RootIptablesConfig
import engine.root.RootModeStartConfig
import engine.root.RootRuntimeLayout
import engine.root.RootStartConfig
import engine.root.buildAsteriskdConfig
import engine.root.buildRootSharedProxyInbounds
import engine.root.tun2SocksInternalProxyPortValue
import engine.root.toRootRuntimeLayout
import engine.vpn.TunOptions
import engine.vpn.toTunOptions
import engine.xray.XrayCoreLogPaths
import engine.xray.XrayProtocols
import engine.xray.XrayTags
import engine.xray.prepareXrayCoreLogPaths
import engine.xray.toJsonStringArray
import engine.xray.xraySniffingDestOverrides
import features.resources.runtime.prepareXrayResourceFilePaths
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

internal data class Tun2SocksStartConfig(
    override val root: RootStartConfig,
    override val localProxyOptions: LocalProxyOptions,
    val hevSocks5TunnelConfig: HevSocks5TunnelConfig,
    val iptablesConfig: RootIptablesConfig,
    override val asteriskdConfig: AsteriskdConfig,
    override val rootEbpfConfig: RootEbpfRuntimeConfig?,
) : RootModeStartConfig

internal val Tun2SocksBaseIptablesConfig = RootIptablesConfig(
    mark = Tun2SocksFwmark,
    ipv4Table = Tun2SocksRouteTable,
    ipv6Table = Tun2SocksRouteTable,
)

internal fun RootConfigBuildContext.buildTun2SocksStartConfig(): Tun2SocksStartConfig {
    val appState = this.appState
    val tunOptions = appState.toTunOptions()
    val localProxyOptions = appState.toLocalProxyOptions()
    val socks5ProxyPort = appState.tun2SocksInternalProxyPortValue()
    val iptablesConfig = buildRootIptablesConfig(base = Tun2SocksBaseIptablesConfig)
    val rootStartConfig = buildRootStartConfig(
        inbounds = appState.buildTun2SocksInbounds(localProxyOptions, socks5ProxyPort),
        dnsHijackInboundTags = listOf(XrayTags.TUN2SOCKS_INBOUND),
    )
    return Tun2SocksStartConfig(
        root = rootStartConfig,
        localProxyOptions = localProxyOptions,
        hevSocks5TunnelConfig = buildHevSocks5TunnelConfig(
            runtimeLayout = rootStartConfig.runtimeLayout,
            coreLogPaths = rootStartConfig.coreLogPaths,
            socks5ProxyPort = socks5ProxyPort,
            tunOptions = tunOptions,
            enableIpv6 = appState.enableIpv6,
        ),
        iptablesConfig = iptablesConfig,
        asteriskdConfig = rootStartConfig.buildAsteriskdConfig(
            mode = AsteriskdMode.Tun2Socks,
            iptablesConfig = iptablesConfig,
            virtualInterfaces = listOf("asterisk0"),
            bypassConsumerChains = AsteriskdBypassConsumerChains(
                ipv4 = listOf(Tun2SocksPreroutingChain, Tun2SocksOutputChain),
                ipv6 = listOf(Tun2SocksPrerouting6Chain, Tun2SocksOutput6Chain),
            ),
        ),
        rootEbpfConfig = buildRootEbpfRuntimeConfig(iptablesConfig),
    )
}

internal fun Context.prepareHevSocks5TunnelConfig(appState: AppState): HevSocks5TunnelConfig {
    val runtimeLayout = prepareXrayResourceFilePaths().toRootRuntimeLayout()
    return buildHevSocks5TunnelConfig(
        runtimeLayout = runtimeLayout,
        coreLogPaths = prepareXrayCoreLogPaths(),
        socks5ProxyPort = appState.tun2SocksInternalProxyPortValue(),
        tunOptions = appState.toTunOptions(),
        enableIpv6 = appState.enableIpv6,
    )
}

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

private fun buildHevSocks5TunnelConfig(
    runtimeLayout: RootRuntimeLayout,
    coreLogPaths: XrayCoreLogPaths,
    socks5ProxyPort: Int,
    tunOptions: TunOptions,
    enableIpv6: Boolean,
): HevSocks5TunnelConfig {
    val dataDir = File(runtimeLayout.dataDir)
    return HevSocks5TunnelConfig(
        executablePath = runtimeLayout.hevSocks5TunnelPath,
        configPath = File(dataDir, HevSocks5TunnelConfigFileName).absolutePath,
        pidPath = File(dataDir, HevSocks5TunnelPidFileName).absolutePath,
        logPath = coreLogPaths.hevSocks5TunnelLogFile(HevSocks5TunnelLogFileName).absolutePath,
        socksAddress = Tun2SocksListenAddress,
        socksPort = socks5ProxyPort,
        mtu = tunOptions.mtu,
        ipv4Address = tunOptions.ipv4Address.address,
        ipv6Address = tunOptions.ipv6Address.address.takeIf { enableIpv6 },
        tunnelName = "asterisk0",
        enableMultiQueue = true,
        enableTcpFastOpen = true,
    )
}
