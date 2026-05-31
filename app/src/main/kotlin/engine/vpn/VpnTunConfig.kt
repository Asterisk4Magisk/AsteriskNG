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
import engine.network.NetworkCidrAddress
import engine.network.isIpv4Address
import engine.network.parseCidrAddressOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import utils.toIntInRangeOrDefault

internal val defaultIpv4TunAddress = VpnDefaults.IPV4_CIDR.toNetworkCidrAddress()
internal val defaultIpv6TunAddress = VpnDefaults.IPV6_CIDR.toNetworkCidrAddress()

internal data class TunOptions(
    val mtu: Int,
    val ipv4Address: NetworkCidrAddress,
    val ipv6Address: NetworkCidrAddress,
    val dnsServers: List<String>,
)

internal fun buildVpnTunInbound(
    appState: AppState,
    tunOptions: TunOptions,
): JsonObject {
    val gateway = buildList {
        add(tunOptions.ipv4Address.toCidrString())
        if (appState.enableIpv6) {
            add(tunOptions.ipv6Address.toCidrString())
        }
    }
    val settings = buildJsonObject {
        put("name", "asterisk0")
        put("mtu", tunOptions.mtu)
        put("gateway", gateway.toJsonStringArray())
        put("userLevel", 0)
        if (appState.effectiveLocalDnsEnabled) {
            put("dns", tunOptions.dnsServers.toJsonStringArray())
        }
    }

    return buildJsonObject {
        put("tag", XrayTags.VPN_TUN_INBOUND)
        put("protocol", XrayProtocols.TUN)
        put("settings", settings)
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

internal fun AppState.toTunOptions(): TunOptions {
    return TunOptions(
        mtu = tunMtuValue(),
        ipv4Address = tunIpv4Address(),
        ipv6Address = tunIpv6Address(),
        dnsServers = listOf(
            tunDefaultDns.trim()
                .takeIf(::isIpv4Address)
                ?: VpnDefaults.IPV4_DNS,
        ),
    )
}

private fun AppState.tunMtuValue(): Int {
    return tunMtu.toIntInRangeOrDefault(VpnDefaults.MTU_MIN..VpnDefaults.MTU_MAX, default = VpnDefaults.MTU)
}

private fun AppState.tunIpv4Address(): NetworkCidrAddress {
    return parseCidrAddressOrNull(tunIpv4Cidr)
        ?.takeIf { address -> !address.address.contains(":") }
        ?: defaultIpv4TunAddress
}

private fun AppState.tunIpv6Address(): NetworkCidrAddress {
    return parseCidrAddressOrNull(tunIpv6Cidr)
        ?.takeIf { address -> address.address.contains(":") }
        ?: defaultIpv6TunAddress
}

private fun String.toNetworkCidrAddress(): NetworkCidrAddress {
    return parseCidrAddressOrNull(this) ?: error("Invalid VPN CIDR: $this")
}

private fun NetworkCidrAddress.toCidrString(): String {
    return "$address/$prefixLength"
}
