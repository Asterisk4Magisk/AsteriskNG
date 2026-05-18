package features.proxy.server.model

internal fun ProxyServer<*>.serverHost(): String {
    return when (this) {
        is HTTP -> server
        is Hysteria2 -> server
        is Shadowsocks -> server
        is Socks -> server
        is Trojan -> server
        is VLESS -> server
        is VMess -> server
        is Wireguard -> server
        else -> ""
    }
}

internal fun String.normalizedServerHost(): String {
    return trim().trim('[', ']')
}
