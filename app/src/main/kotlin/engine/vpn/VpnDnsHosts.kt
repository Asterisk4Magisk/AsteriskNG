package engine.vpn

import app.AppState
import features.logs.AndroidAppLogger
import engine.network.isIpv4Address
import engine.network.isIpv6Address
import features.proxy.server.model.HTTP
import features.proxy.server.model.Hysteria2
import features.proxy.server.model.ProxyServer
import features.proxy.server.model.Shadowsocks
import features.proxy.server.model.Socks
import features.proxy.server.model.Trojan
import features.proxy.server.model.VLESS
import features.proxy.server.model.VMess
import features.proxy.server.model.Wireguard
import java.net.InetAddress

internal fun AppState.xrayDnsHosts(proxyServers: List<ProxyServer<*>>): List<String> {
    if (!enableResolveProxyServerDomain) return dnsHosts
    return (dnsHosts + proxyServers.mapNotNull { server -> server.toResolvedDnsHostEntry() }).distinct()
}

private fun ProxyServer<*>.toResolvedDnsHostEntry(): String? {
    val host = serverHostForDnsResolution().normalizedHost()
    if (host.isBlank() || isIpv4Address(host) || isIpv6Address(host) || host.equals("localhost", ignoreCase = true)) {
        return null
    }
    val addresses = host.resolveHostAddresses()
    if (addresses.isEmpty()) return null
    return "$host:${addresses.joinToString(",")}"
}

private fun String.resolveHostAddresses(): List<String> {
    val host = this
    return runCatching {
        InetAddress.getAllByName(host)
            .mapNotNull { address -> address.hostAddress?.substringBefore('%') }
            .filter(String::isNotBlank)
            .distinct()
    }.onFailure { error ->
        AndroidAppLogger.warn(LogTag, "Failed to resolve proxy server host: $host", error)
    }.getOrDefault(emptyList())
}

private fun ProxyServer<*>.serverHostForDnsResolution(): String {
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

private fun String.normalizedHost(): String {
    return trim().trim('[', ']')
}

private const val LogTag = "VpnDnsHosts"
