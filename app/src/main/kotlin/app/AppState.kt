// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app

import app.modes.ColorModeSystem
import app.modes.LanguageModeSystem
import app.modes.ProxyAppListModeGlobal
import app.modes.ProxyServerListLayoutSingle
import app.modes.ProxyServerListSortDefault
import app.modes.RunModeVpnService
import engine.root.DefaultRootHttpProxyPort
import engine.root.RootBpf2SocksDefaultBridgePort
import engine.tun2socks.DefaultTun2SocksProxyPort
import engine.tproxy.DefaultTproxyPort
import engine.vpn.VpnDefaults
import engine.xray.DefaultDirectDnsDomains
import engine.xray.DefaultFragmentInterval
import engine.xray.DefaultFragmentLength
import engine.xray.DefaultFragmentPackets
import engine.xray.DefaultMuxConcurrency
import engine.xray.DefaultMuxUdp443Mode
import engine.xray.DefaultMuxXudpConcurrency
import features.resources.ResourceFileSourceLoyalsoldierGithub
import features.resources.ResourceFileLoyalsoldierGeoIpUrl
import features.resources.ResourceFileLoyalsoldierGeoSiteUrl
import features.resources.ResourceFileDirectCidrIpv4Url
import features.resources.ResourceFileDirectCidrIpv6Url
import features.resources.ResourceFileV2FlyGeoIpOnlyCnPrivateUrl
import features.subscription.DefaultSubscriptionUserAgent
import features.routing.model.RouteRule

data class AppState(
    val colorMode: Int = ColorModeSystem,
    val languageMode: Int = LanguageModeSystem,
    val seedIndex: Int = 0,

    val subscriptionGroups: List<SubscriptionGroupState> = DefaultSubscriptionGroups,
    val nextSubscriptionGroupId: Int = 4,
    val enableAllProxyGroup: Boolean = false,
    val enableDeletionConfirmation: Boolean = true,

    val runMode: Int = RunModeVpnService,
    val enableResolveProxyServerDomain: Boolean = false,

    val enableVpnLocalDns: Boolean = true,
    val localProxyPort: String = VpnDefaults.LOCAL_PROXY_PORT.toString(),
    val enableDynamicLocalProxyPort: Boolean = false,
    val localProxyListenAllInterfaces: Boolean = false,
    val localProxyUsername: String = "",
    val localProxyPassword: String = "",
    val enableVpnAppendHttpProxy: Boolean = false,
    val enableVpnHevTun: Boolean = false,
    val tunMtu: String = VpnDefaults.MTU.toString(),
    val tunVpnDns: String = VpnDefaults.IPV4_DNS,
    val tunIpv4Cidr: String = VpnDefaults.IPV4_CIDR,
    val tunIpv6Cidr: String = VpnDefaults.IPV6_CIDR,

    val proxyServers: List<ProxyServerState> = emptyList(),
    val nextProxyServerId: Int = 10,
    val selectedProxyServerId: Int = 1,
    val proxyServerListLayout: Int = ProxyServerListLayoutSingle,
    val proxyServerListSort: Int = ProxyServerListSortDefault,
    val proxyRunning: Boolean = false,

    val routeDomainStrategy: Int = 0,
    val defaultRouteOutboundTag: String = DefaultRouteOutboundTag,
    val routeRules: List<RouteRule> = DefaultRouteRules,
    val nextRouteRuleId: Int = 10,

    val coreLogLevel: Int = 3,
    val enableAccessLog: Boolean = false,
    val resourceFileSource: Int = ResourceFileSourceLoyalsoldierGithub,
    val customResourceFileGeoIpUrl: String = ResourceFileLoyalsoldierGeoIpUrl,
    val customResourceFileGeoSiteUrl: String = ResourceFileLoyalsoldierGeoSiteUrl,
    val customResourceFileGeoIpOnlyCnPrivateUrl: String = ResourceFileV2FlyGeoIpOnlyCnPrivateUrl,
    val customResourceFileDirectCidrIpv4Url: String = ResourceFileDirectCidrIpv4Url,
    val customResourceFileDirectCidrIpv6Url: String = ResourceFileDirectCidrIpv6Url,
    val customResourceFiles: List<CustomResourceFileState> = emptyList(),
    val nextCustomResourceFileId: Int = 1,
    val resourceFileUserAgent: String = DefaultSubscriptionUserAgent,
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

    val enableTrafficStatsNotification: Boolean = false,
    val enableBroadcastControl: Boolean = false,
    val enableIpv6: Boolean = false,
    val enableIpv6Prefer: Boolean = false,
    val enableFakeDns: Boolean = false,
    val proxyDns: List<String> = VpnDefaults.PROXY_DNS_SERVERS,
    val directDns: List<String> = VpnDefaults.DIRECT_DNS_SERVERS,
    val directDnsDomains: List<String> = DefaultDirectDnsDomains,
    val enableDirectDnsForProxyServerDomains: Boolean = true,
    val dnsHosts: List<String> = emptyList(),

    val transparentProxyPort: String = DefaultTproxyPort.toString(),
    val enableRootBootScript: Boolean = false,
    val enableRootEbpfRules: Boolean = false,
    val enableRootEbpfDirectCidrBypass: Boolean = false,
    val enableRootIpv6Disabler: Boolean = false,
    val bpf2SocksBridgePort: String = RootBpf2SocksDefaultBridgePort.toString(),
    val socks5ProxyPort: String = DefaultTun2SocksProxyPort.toString(),
    val enableHttpProxy: Boolean = false,
    val httpProxyPort: String = DefaultRootHttpProxyPort.toString(),

    val externalInterfaces: List<String> = emptyList(),
    val ignoredInterfaces: List<String> = emptyList(),
    val privateAddressCidrs: List<String> = emptyList(),

    val proxyAppListMode: Int = ProxyAppListModeGlobal,
    val proxyAppListSelectedApps: List<String> = emptyList(),
)

val AppState.effectiveLocalDnsEnabled: Boolean
    get() = enableVpnLocalDns

val AppState.effectiveFakeDnsEnabled: Boolean
    get() = effectiveLocalDnsEnabled && enableFakeDns
