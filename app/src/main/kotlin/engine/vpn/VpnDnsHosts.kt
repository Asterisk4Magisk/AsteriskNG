package engine.vpn

import app.AppState
import features.logs.AndroidAppLogger
import engine.network.isIpv4Address
import engine.network.isIpv6Address
import features.proxy.server.model.normalizedServerHost
import java.net.InetAddress

internal fun AppState.xrayDnsHosts(proxyServerHosts: List<String>): List<String> {
    if (!enableResolveProxyServerDomain) return dnsHosts
    return (dnsHosts + proxyServerHosts.mapNotNull { host -> host.toResolvedDnsHostEntry() }).distinct()
}

private fun String.toResolvedDnsHostEntry(): String? {
    val host = normalizedServerHost()
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

private const val LogTag = "VpnDnsHosts"
