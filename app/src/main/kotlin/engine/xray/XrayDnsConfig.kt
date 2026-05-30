// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.xray

import app.AppState
import app.effectiveFakeDnsEnabled
import engine.network.isIpAddress
import engine.network.isIpv4Address
import engine.vpn.VpnDefaults
import features.proxy.server.model.normalizedServerHost
import features.proxy.server.model.serverHost
import org.json.JSONArray
import org.json.JSONObject

internal fun buildXrayDnsConfig(
    appState: AppState,
    proxyDnsServers: List<String>,
    directDnsServers: List<String>,
    directDnsDomains: List<String>,
    dnsHosts: List<String>,
    startupProxyServerDomains: List<String> = emptyList(),
): JSONObject {
    return JSONObject()
        .put(
            "servers",
            appState.xrayDnsServers(
                proxyDnsServers = proxyDnsServers,
                directDnsServers = directDnsServers,
                directDnsDomains = directDnsDomains,
                startupProxyServerDomains = startupProxyServerDomains,
            ),
        )
        .put("queryStrategy", if (appState.enableIpv6) "UseIP" else "UseIPv4")
        .put("tag", XrayTags.PROXY_DNS)
        .apply {
            val hosts = dnsHosts.toDnsHostsJson()
            if (hosts.length() > 0) {
                put("hosts", hosts)
            }
        }
}

internal fun buildXrayFakeDnsConfig(appState: AppState): Any {
    if (!appState.enableIpv6) {
        return JSONObject()
            .put("ipPool", XrayFakeDnsIpv4Pool)
            .put("poolSize", XrayFakeDnsIpv4OnlyPoolSize)
    }
    return JSONArray()
        .put(
            JSONObject()
                .put("ipPool", XrayFakeDnsIpv4Pool)
                .put("poolSize", XrayFakeDnsDualStackPoolSize),
        )
        .put(
            JSONObject()
                .put("ipPool", XrayFakeDnsIpv6Pool)
                .put("poolSize", XrayFakeDnsDualStackPoolSize),
        )
}

internal data class XrayDnsRoutingOptions(
    val routeProxyDns: Boolean,
    val routeDirectDns: Boolean,
)

internal fun AppState.xrayDnsRoutingOptions(
    proxyDnsServers: List<String>,
    directDnsServers: List<String>,
    directDnsDomains: List<String>,
    startupProxyServerDomains: List<String> = emptyList(),
): XrayDnsRoutingOptions {
    val effectiveDirectDnsDomains = xrayDirectDnsDomains(
        directDnsDomains = directDnsDomains,
        startupProxyServerDomains = startupProxyServerDomains,
    )
    return XrayDnsRoutingOptions(
        routeProxyDns = xrayProxyDnsServers(
            proxyDnsServers = proxyDnsServers,
            directDnsServers = directDnsServers,
            directDnsDomains = effectiveDirectDnsDomains,
        ).isNotEmpty(),
        routeDirectDns = effectiveDirectDnsDomains.isNotEmpty() &&
            xrayDirectDnsServers(directDnsServers).isNotEmpty(),
    )
}

internal fun JSONObject.putXrayFakeDnsConfig(appState: AppState): JSONObject {
    if (appState.effectiveFakeDnsEnabled) {
        put("fakedns", buildXrayFakeDnsConfig(appState))
    } else {
        remove("fakedns")
    }
    return this
}

internal fun AppState.xrayProxyDnsServers(
    proxyDnsServers: List<String>,
    directDnsServers: List<String>,
    directDnsDomains: List<String>? = null,
): List<String> {
    val sanitizedProxyDns = proxyDnsServers.toSanitizedDnsServers()
    if (sanitizedProxyDns.isNotEmpty()) {
        return sanitizedProxyDns
    }
    val hasDirectDns = directDnsServers.toSanitizedDnsServers().isNotEmpty() &&
        (directDnsDomains == null || directDnsDomains.isNotEmpty())
    return if (!hasDirectDns) {
        listOf(
            vpnDefaultDns.trim()
                .takeIf(::isIpv4Address)
                ?: VpnDefaults.IPV4_DNS,
        )
    } else {
        emptyList()
    }
}

internal fun AppState.xrayDirectDnsServers(directDnsServers: List<String>): List<String> {
    return directDnsServers.toSanitizedDnsServers()
}

internal fun AppState.xrayDirectDnsDomains(
    directDnsDomains: List<String>,
    startupProxyServerDomains: List<String> = emptyList(),
): List<String> {
    return (directDnsDomains.toSanitizedDnsServers() + startupProxyServerDomains).distinct()
}

internal fun Iterable<XrayProxyOutboundServer>.startupProxyServerDnsDomains(): List<String> {
    return map { outbound -> outbound.server.serverHost() }.startupProxyServerHostDnsDomains()
}

internal fun Iterable<String>.startupProxyServerHostDnsDomains(): List<String> {
    return mapNotNull { host -> host.toXrayDnsDomainRule() }.distinct()
}

private fun AppState.xrayDnsServers(
    proxyDnsServers: List<String>,
    directDnsServers: List<String>,
    directDnsDomains: List<String>,
    startupProxyServerDomains: List<String>,
): JSONArray {
    val effectiveDirectDnsDomains = xrayDirectDnsDomains(directDnsDomains, startupProxyServerDomains)
    val effectiveDirectDnsServers = xrayDirectDnsServers(directDnsServers)
        .takeIf { effectiveDirectDnsDomains.isNotEmpty() }
        .orEmpty()
    return JSONArray().apply {
        if (effectiveFakeDnsEnabled) {
            put("fakedns")
        }
        effectiveDirectDnsServers.forEach { server ->
            put(
                JSONObject()
                    .put("address", server)
                    .put("domains", effectiveDirectDnsDomains.toJsonStringArray())
                    .put("skipFallback", true)
                    .put("tag", XrayTags.DIRECT_DNS),
            )
        }
        xrayProxyDnsServers(
            proxyDnsServers = proxyDnsServers,
            directDnsServers = directDnsServers,
            directDnsDomains = effectiveDirectDnsDomains,
        ).forEach(::put)
    }
}

private fun String.toXrayDnsDomainRule(): String? {
    val host = normalizedServerHost()
    if (host.isBlank() || host.equals("localhost", ignoreCase = true) || isIpAddress(host)) {
        return null
    }
    return "domain:$host"
}

private fun List<String>.toSanitizedDnsServers(): List<String> {
    return map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
}

private fun List<String>.toDnsHostsJson(): JSONObject {
    return JSONObject().also { hosts ->
        forEach { entry ->
            val separatorIndex = entry.indexOf(':')
            if (separatorIndex <= 0 || separatorIndex == entry.lastIndex) {
                return@forEach
            }
            val domain = entry.substring(0, separatorIndex).trim()
            val addresses = entry.substring(separatorIndex + 1)
                .split(",")
                .map { address -> address.trim().trim('[', ']') }
                .filter(String::isNotEmpty)
            if (domain.isNotEmpty() && addresses.isNotEmpty()) {
                hosts.put(
                    domain,
                    if (addresses.size == 1) addresses.first() else addresses.toJsonStringArray(),
                )
            }
        }
    }
}
