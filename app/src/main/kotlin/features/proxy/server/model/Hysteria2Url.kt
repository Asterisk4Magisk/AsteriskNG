// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.model

import io.ktor.http.Url

internal data class Hysteria2Url(
    val url: Url,
    val authorityPorts: String?,
)

/** Normalize the official multi-port authority before Ktor requires a single integer port. */
internal fun parseHysteria2Url(value: String): Hysteria2Url {
    val schemeEnd = value.indexOf("://")
    require(schemeEnd > 0) { "Invalid Hysteria2 URL" }
    val authorityStart = schemeEnd + 3
    val authorityEnd = value.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
        .takeIf { it >= 0 } ?: value.length
    val authority = value.substring(authorityStart, authorityEnd)
    val hostStart = authority.lastIndexOf('@') + 1
    val endpoint = authority.substring(hostStart)
    require(endpoint.isNotEmpty()) { "Hysteria2 server is required" }

    val portSeparator = if (endpoint.startsWith('[')) {
        val hostEnd = endpoint.indexOf(']')
        require(hostEnd > 0) { "Invalid Hysteria2 IPv6 address" }
        when {
            hostEnd == endpoint.lastIndex -> -1
            endpoint[hostEnd + 1] == ':' -> hostStart + hostEnd + 1
            else -> throw IllegalArgumentException("Invalid Hysteria2 endpoint")
        }
    } else {
        require(endpoint.count { it == ':' } <= 1) { "Hysteria2 IPv6 addresses must use brackets" }
        endpoint.indexOf(':').takeIf { it >= 0 }?.plus(hostStart) ?: -1
    }

    val rawPorts = if (portSeparator < 0) null else authority.substring(portSeparator + 1)
    if (rawPorts != null) {
        require(rawPorts.isNotBlank()) { "Hysteria2 port is required after ':'" }
        requireValidHysteria2Ports(rawPorts)
    }
    val firstPort = rawPorts?.substringBefore(',')?.substringBefore('-')?.trim()?.toInt()
        ?: DefaultHysteria2Port
    val hostAuthority = if (portSeparator < 0) authority else authority.substring(0, portSeparator)
    val normalized = value.substring(0, authorityStart) + hostAuthority + ":" + firstPort + value.substring(authorityEnd)
    return Hysteria2Url(
        url = Url(normalized),
        authorityPorts = rawPorts?.takeIf { ',' in it || '-' in it },
    )
}

internal fun requireValidHysteria2Ports(ports: String) {
    val issues = buildList { validateHysteriaMultiPorts(ports) }
    require(issues.isEmpty()) { "Invalid Hysteria2 port hopping range" }
}

private const val DefaultHysteria2Port = 443
