// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app

import app.modes.ColorModeSystem
import app.modes.LanguageModeSystem
import app.modes.ProxyAppListModeGlobal
import app.modes.RunModeTproxy
import app.modes.RunModeVpnService
import engine.tproxy.DefaultTproxyHttpPort
import engine.tproxy.DefaultTproxyPort
import engine.tproxy.DefaultTproxySocks5Port
import engine.vpn.VpnDefaults
import engine.xray.DefaultDirectDnsDomains
import engine.xray.DefaultFragmentInterval
import engine.xray.DefaultFragmentLength
import engine.xray.DefaultFragmentPackets
import engine.xray.DefaultMuxConcurrency
import engine.xray.DefaultMuxUdp443Mode
import engine.xray.DefaultMuxXudpConcurrency
import features.resources.ResourceFileSourceLoyalsoldierGithub
import features.routing.model.RouteRule

data class AppState(
    val colorMode: Int = ColorModeSystem,
    val languageMode: Int = LanguageModeSystem,
    val seedIndex: Int = 0,

    val subscriptionGroups: List<SubscriptionGroupState> = DefaultSubscriptionGroups,
    val nextSubscriptionGroupId: Int = 4,
    val enableAllProxyGroup: Boolean = false,

    val runMode: Int = RunModeVpnService,
    val enableResolveProxyServerDomain: Boolean = false,

    val enableVpnLocalDns: Boolean = true,
    val localProxyPort: String = VpnDefaults.LOCAL_PROXY_PORT.toString(),
    val enableDynamicLocalProxyPort: Boolean = false,
    val localProxyListenAllInterfaces: Boolean = false,
    val localProxyUsername: String = "",
    val localProxyPassword: String = "",
    val enableVpnAppendHttpProxy: Boolean = false,
    val vpnMtu: String = VpnDefaults.MTU.toString(),
    val vpnDefaultDns: String = VpnDefaults.IPV4_DNS,
    val vpnIpv4Cidr: String = VpnDefaults.IPV4_CIDR,
    val vpnIpv6Cidr: String = VpnDefaults.IPV6_CIDR,

    val proxyServers: List<ProxyServerState> = emptyList(),
    val nextProxyServerId: Int = 10,
    val selectedProxyServerId: Int = 1,
    val proxyRunning: Boolean = false,

    val routeDomainStrategy: Int = 0,
    val routeRules: List<RouteRule> = DefaultRouteRules,
    val nextRouteRuleId: Int = 10,

    val coreLogLevel: Int = 3,
    val enableAccessLog: Boolean = false,
    val resourceFileSource: Int = ResourceFileSourceLoyalsoldierGithub,
    val enableSniffing: Boolean = true,
    val enableSniffingRouteOnly: Boolean = true,

    val enableMux: Boolean = false,
    val muxConcurrency: String = DefaultMuxConcurrency,
    val muxXudpConcurrency: String = DefaultMuxXudpConcurrency,
    val muxXudpProxyUdp443: Int = DefaultMuxUdp443Mode,

    val enableFragment: Boolean = false,
    val fragmentPackets: String = DefaultFragmentPackets,
    val fragmentLength: String = DefaultFragmentLength,
    val fragmentInterval: String = DefaultFragmentInterval,

    val enableIpv6: Boolean = false,
    val enableIpv6Prefer: Boolean = false,
    val enableFakeDns: Boolean = false,
    val proxyDns: List<String> = VpnDefaults.PROXY_DNS_SERVERS,
    val directDns: List<String> = VpnDefaults.DIRECT_DNS_SERVERS,
    val directDnsDomains: List<String> = DefaultDirectDnsDomains,
    val enableDirectDnsForProxyServerDomains: Boolean = true,
    val dnsHosts: List<String> = emptyList(),

    val transparentProxyPort: String = DefaultTproxyPort.toString(),
    val enableTproxyBootScript: Boolean = false,
    val enableSocks5Proxy: Boolean = false,
    val socks5ProxyPort: String = DefaultTproxySocks5Port.toString(),
    val enableHttpProxy: Boolean = false,
    val httpProxyPort: String = DefaultTproxyHttpPort.toString(),

    val externalInterfaces: List<String> = emptyList(),
    val ignoredInterfaces: List<String> = emptyList(),
    val privateAddressCidrs: List<String> = emptyList(),

    val proxyAppListMode: Int = ProxyAppListModeGlobal,
    val proxyAppListSelectedApps: List<String> = emptyList(),
)

val AppState.effectiveLocalDnsEnabled: Boolean
    get() = runMode == RunModeTproxy || enableVpnLocalDns

val AppState.effectiveFakeDnsEnabled: Boolean
    get() = effectiveLocalDnsEnabled && enableFakeDns
