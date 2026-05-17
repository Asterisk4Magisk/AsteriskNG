package engine.xray

import app.AppState
import app.effectiveFakeDnsEnabled
import app.effectiveLocalDnsEnabled
import engine.vpn.VpnDefaults
import engine.network.isIpv4Address
import org.json.JSONArray
import org.json.JSONObject

internal fun buildXrayDnsConfig(
    appState: AppState,
    remoteDnsServers: List<String>,
    domesticDnsServers: List<String>,
    dnsHosts: List<String>,
): JSONObject {
    return JSONObject()
        .put("servers", appState.xrayDnsServers(remoteDnsServers, domesticDnsServers))
        .put("queryStrategy", if (appState.enableIpv6) "UseIP" else "UseIPv4")
        .put("tag", XrayTags.REMOTE_DNS)
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

internal fun AppState.xrayRemoteDnsServers(
    remoteDnsServers: List<String>,
    domesticDnsServers: List<String>,
): List<String> {
    val sanitizedRemoteDns = remoteDnsServers.toSanitizedDnsServers()
    if (sanitizedRemoteDns.isNotEmpty()) {
        return sanitizedRemoteDns
    }
    return if (domesticDnsServers.toSanitizedDnsServers().isEmpty()) {
        listOf(
            vpnDefaultDns.trim()
                .takeIf(::isIpv4Address)
                ?: VpnDefaults.IPV4_DNS,
        )
    } else {
        emptyList()
    }
}

internal fun AppState.xrayDomesticDnsServers(domesticDnsServers: List<String>): List<String> {
    return domesticDnsServers.toSanitizedDnsServers()
}

internal fun AppState.shouldUseXrayDnsOutbound(): Boolean {
    return effectiveLocalDnsEnabled
}

private fun AppState.xrayDnsServers(
    remoteDnsServers: List<String>,
    domesticDnsServers: List<String>,
): JSONArray {
    return JSONArray().apply {
        if (effectiveFakeDnsEnabled) {
            put("fakedns")
        }
        xrayDomesticDnsServers(domesticDnsServers).forEach { server ->
            put(
                JSONObject()
                    .put("address", server)
                    .put("domains", listOf(XrayDomesticDnsDomain).toJsonStringArray())
                    .put("skipFallback", true)
                    .put("tag", XrayTags.DOMESTIC_DNS),
            )
        }
        xrayRemoteDnsServers(remoteDnsServers, domesticDnsServers).forEach(::put)
    }
}

private const val XrayDomesticDnsDomain = "geosite:cn"

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
