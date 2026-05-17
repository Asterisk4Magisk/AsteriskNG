package engine.vpn

object VpnDefaults {
    const val LOCAL_PROXY_PORT = 10_808
    const val VPN_APPEND_HTTP_PROXY_FALLBACK_PORT = 10_809
    const val MTU = 1500
    const val MTU_MIN = 1280
    const val MTU_MAX = 65_535
    const val IPV4_DNS = "1.1.1.1"
    const val REMOTE_DNS = "https://1.1.1.1/dns-query"
    val REMOTE_DNS_SERVERS = listOf(REMOTE_DNS)
    val DOMESTIC_DNS_SERVERS = listOf("223.5.5.5", "119.29.29.29")
    const val IPV4_CIDR = "172.19.0.1/30"
    const val IPV6_CIDR = "fdfe:dcba:9876::1/126"
}
