// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

import app.AppState
import app.effectiveFakeDnsEnabled
import features.settings.sheets.sanitizeMuxUdp443Index

internal data class ProxySettingsDraft(
    val transparentProxyPort: String = "",
    val bpf2SocksBridgePort: String = "",
    val socks5ProxyPort: String = "",
    val enableHttpProxy: Boolean = false,
    val httpProxyPort: String = "",
)

internal fun AppState.toProxySettingsDraft(): ProxySettingsDraft {
    return ProxySettingsDraft(
        transparentProxyPort = transparentProxyPort,
        bpf2SocksBridgePort = bpf2SocksBridgePort,
        socks5ProxyPort = socks5ProxyPort,
        enableHttpProxy = enableHttpProxy,
        httpProxyPort = httpProxyPort,
    )
}

internal data class TunSettingsDraft(
    val mtu: String = "",
    val vpnDns: String = "",
    val ipv4Cidr: String = "",
    val ipv6Cidr: String = "",
)

internal fun AppState.toTunSettingsDraft(): TunSettingsDraft {
    return TunSettingsDraft(
        mtu = tunMtu,
        vpnDns = tunVpnDns,
        ipv4Cidr = tunIpv4Cidr,
        ipv6Cidr = tunIpv6Cidr,
    )
}

internal data class LocalProxySettingsDraft(
    val port: String = "",
    val enableDynamicPort: Boolean = false,
    val listenAllInterfaces: Boolean = false,
    val username: String = "",
    val password: String = "",
)

internal fun AppState.toLocalProxySettingsDraft(): LocalProxySettingsDraft {
    return LocalProxySettingsDraft(
        port = localProxyPort,
        enableDynamicPort = enableDynamicLocalProxyPort,
        listenAllInterfaces = localProxyListenAllInterfaces,
        username = localProxyUsername,
        password = localProxyPassword,
    )
}

internal data class DnsSettingsDraft(
    val enableVpnLocalDns: Boolean = true,
    val enableFakeDns: Boolean = false,
    val enableResolveProxyServerDomain: Boolean = false,
    val proxyDns: List<String> = emptyList(),
    val directDns: List<String> = emptyList(),
    val directDnsDomains: List<String> = emptyList(),
    val enableDirectDnsForProxyServerDomains: Boolean = true,
    val dnsHosts: List<String> = emptyList(),
)

internal fun AppState.toDnsSettingsDraft(): DnsSettingsDraft {
    return DnsSettingsDraft(
        enableVpnLocalDns = enableVpnLocalDns,
        enableFakeDns = effectiveFakeDnsEnabled,
        enableResolveProxyServerDomain = enableResolveProxyServerDomain,
        proxyDns = proxyDns,
        directDns = directDns,
        directDnsDomains = directDnsDomains,
        enableDirectDnsForProxyServerDomains = enableDirectDnsForProxyServerDomains,
        dnsHosts = dnsHosts,
    )
}

internal data class MuxSettingsDraft(
    val enabled: Boolean = false,
    val concurrency: String = "",
    val xudpConcurrency: String = "",
    val xudpProxyUdp443: Int = 0,
)

internal fun AppState.toMuxSettingsDraft(): MuxSettingsDraft {
    return MuxSettingsDraft(
        enabled = enableMux,
        concurrency = muxConcurrency,
        xudpConcurrency = muxXudpConcurrency,
        xudpProxyUdp443 = sanitizeMuxUdp443Index(muxXudpProxyUdp443),
    )
}

internal data class FragmentSettingsDraft(
    val enabled: Boolean = false,
    val packets: String = "",
    val length: String = "",
    val interval: String = "",
)

internal fun AppState.toFragmentSettingsDraft(): FragmentSettingsDraft {
    return FragmentSettingsDraft(
        enabled = enableFragment,
        packets = fragmentPackets,
        length = fragmentLength,
        interval = fragmentInterval,
    )
}
