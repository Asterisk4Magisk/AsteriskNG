// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.tproxy


internal fun TproxyIptablesConfig.ipv4Family(): TproxyIptablesFamily {
    return TproxyIptablesFamily(
        command = TproxyIptablesCommand,
        ipCommand = TproxyIpCommand,
        routeTable = ipv4Table,
        routeDestination = "default",
        legacyPreroutingChain = TproxyPreroutingChain,
        preroutingTargetChain = TproxyPreroutingTargetChain,
        outputChain = TproxyOutputChain,
        dnsOutputChain = TproxyDnsOutputChain,
        tproxyOnIp = "0.0.0.0",
        localInterfaceCidrs = localInterfaceIpv4Cidrs,
        proxyPrivateCidrs = proxyPrivateIpv4Cidrs,
        bypassPrivateCidrs = bypassPrivateIpv4Cidrs,
    )
}

internal fun TproxyIptablesConfig.ipv6Family(dummy: Boolean): TproxyIptablesFamily {
    return TproxyIptablesFamily(
        command = TproxyIp6tablesCommand,
        ipCommand = TproxyIp6Command,
        routeTable = ipv6Table,
        routeDestination = "default",
        legacyPreroutingChain = TproxyPrerouting6Chain,
        preroutingTargetChain = TproxyPrerouting6TargetChain,
        outputChain = TproxyOutput6Chain,
        dnsOutputChain = TproxyDnsOutput6Chain,
        tproxyOnIp = "::",
        localInterfaceCidrs = localInterfaceIpv6Cidrs,
        proxyPrivateCidrs = proxyPrivateIpv6Cidrs,
        bypassPrivateCidrs = bypassPrivateIpv6Cidrs,
        dummy = DummyIptablesConfig.takeIf { dummy },
    )
}

internal fun hasGlobalIpv6AddressTest(): String {
    return "$TproxyIp6Command addr show scope global 2>/dev/null | grep -q 'inet6 '"
}

internal data class TproxyIptablesFamily(
    val command: String,
    val ipCommand: String,
    val routeTable: String,
    val routeDestination: String,
    val legacyPreroutingChain: String,
    val preroutingTargetChain: String,
    val outputChain: String,
    val dnsOutputChain: String,
    val tproxyOnIp: String,
    val localInterfaceCidrs: List<String>,
    val proxyPrivateCidrs: List<String>,
    val bypassPrivateCidrs: List<String>,
    val dummy: TproxyDummyIptablesConfig? = null,
)

internal data class TproxyDummyIptablesConfig(
    val device: String,
    val address: String,
    val mark: String,
    val routeTable: String,
    val outputChain: String,
    val preroutingChain: String,
)

private val DummyIptablesConfig = TproxyDummyIptablesConfig(
    device = TproxyDummyDevice,
    address = TproxyDummyAddress,
    mark = TproxyDummyFwmark,
    routeTable = TproxyDummyRouteTable,
    outputChain = "ASTERISK_TPROXY6_DUMMY",
    preroutingChain = "ASTERISK_TPROXY6_DUMMY_TARGET",
)
