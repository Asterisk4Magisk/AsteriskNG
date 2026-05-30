// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import app.AppState
import app.effectiveFakeDnsEnabled
import app.effectiveLocalDnsEnabled
import engine.xray.XrayProtocols
import engine.xray.XrayTags
import engine.xray.toJsonStringArray
import engine.xray.xraySniffingDestOverrides
import engine.network.isIpv4Address
import engine.network.parseCidrAddressOrNull
import org.json.JSONObject

internal val defaultIpv4TunAddress = VpnDefaults.IPV4_CIDR.toVpnCidrAddress()
internal val defaultIpv6TunAddress = VpnDefaults.IPV6_CIDR.toVpnCidrAddress()

internal data class VpnCidrAddress(
    val address: String,
    val prefixLength: Int,
)

internal data class VpnTunOptions(
    val mtu: Int,
    val ipv4Address: VpnCidrAddress,
    val ipv6Address: VpnCidrAddress,
    val dnsServers: List<String>,
)

internal fun buildVpnTunInbound(
    appState: AppState,
    tunOptions: VpnTunOptions,
): JSONObject {
    val gateway = buildList {
        add(tunOptions.ipv4Address.toCidrString())
        if (appState.enableIpv6) {
            add(tunOptions.ipv6Address.toCidrString())
        }
    }
    val settings = JSONObject()
        .put("name", "asterisk0")
        .put("mtu", tunOptions.mtu)
        .put("gateway", gateway.toJsonStringArray())
        .put("userLevel", 0)
    if (appState.effectiveLocalDnsEnabled) {
        settings.put("dns", tunOptions.dnsServers.toJsonStringArray())
    }

    return JSONObject()
        .put("tag", XrayTags.TUN_INBOUND)
        .put("protocol", XrayProtocols.TUN)
        .put("settings", settings)
        .put(
            "sniffing",
            JSONObject()
                .put("enabled", appState.enableSniffing)
                .put("destOverride", xraySniffingDestOverrides(appState.effectiveFakeDnsEnabled).toJsonStringArray())
                .put("routeOnly", appState.enableSniffingRouteOnly),
        )
}

internal fun AppState.toVpnTunOptions(): VpnTunOptions {
    return VpnTunOptions(
        mtu = vpnMtuValue(),
        ipv4Address = vpnIpv4TunAddress(),
        ipv6Address = vpnIpv6TunAddress(),
        dnsServers = listOf(
            vpnDefaultDns.trim()
                .takeIf(::isIpv4Address)
                ?: VpnDefaults.IPV4_DNS,
        ),
    )
}

private fun AppState.vpnMtuValue(): Int {
    return vpnMtu.toIntOrNull()?.takeIf { it in VpnDefaults.MTU_MIN..VpnDefaults.MTU_MAX } ?: VpnDefaults.MTU
}

private fun AppState.vpnIpv4TunAddress(): VpnCidrAddress {
    return vpnIpv4Cidr.toVpnCidrAddressOrNull()
        ?.takeIf { address -> !address.address.contains(":") }
        ?: defaultIpv4TunAddress
}

private fun AppState.vpnIpv6TunAddress(): VpnCidrAddress {
    return vpnIpv6Cidr.toVpnCidrAddressOrNull()
        ?.takeIf { address -> address.address.contains(":") }
        ?: defaultIpv6TunAddress
}

private fun String.toVpnCidrAddress(): VpnCidrAddress {
    return toVpnCidrAddressOrNull() ?: error("Invalid VPN CIDR: $this")
}

private fun String.toVpnCidrAddressOrNull(): VpnCidrAddress? {
    return parseCidrAddressOrNull(this)?.let { cidr ->
        VpnCidrAddress(
            address = cidr.address,
            prefixLength = cidr.prefixLength,
        )
    }
}

private fun VpnCidrAddress.toCidrString(): String {
    return "$address/$prefixLength"
}
