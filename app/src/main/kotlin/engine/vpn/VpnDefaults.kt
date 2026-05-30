// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

object VpnDefaults {
    const val LOCAL_PROXY_PORT = 10_808
    const val VPN_APPEND_HTTP_PROXY_FALLBACK_PORT = 10_809
    const val MTU = 1500
    const val MTU_MIN = 1280
    const val MTU_MAX = 65_535
    const val IPV4_DNS = "1.1.1.1"
    val PROXY_DNS_SERVERS = listOf("https://1.1.1.1/dns-query")
    val DIRECT_DNS_SERVERS = listOf("quic+local://223.5.5.5")
    const val IPV4_CIDR = "172.19.0.1/30"
    const val IPV6_CIDR = "fdfe:dcba:9876::1/126"
}
