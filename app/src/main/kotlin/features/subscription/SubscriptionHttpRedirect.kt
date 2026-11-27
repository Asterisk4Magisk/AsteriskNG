// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription

import java.net.IDN
import java.net.URI
import java.net.URL

internal fun isSubscriptionRedirect(status: Int): Boolean =
    status == 301 || status == 302 || status == 303 || status == 307 || status == 308

internal fun resolveSubscriptionRedirect(current: URI, location: String): URI {
    val reference = URI(location)
    val target = if (reference.scheme == null && reference.rawAuthority == null && reference.rawPath.isNullOrEmpty()) {
        // URI.resolve drops the last base path segment for a query-only reference.
        val base = current.toString().substringBefore('#')
        val withQuery = reference.rawQuery?.let { "${base.substringBefore('?')}?$it" } ?: base
        URI(withQuery + reference.rawFragment?.let { "#$it" }.orEmpty())
    } else {
        current.resolve(reference)
    }
    require(target.subscriptionOriginOrNull() != null) { "Invalid subscription redirect" }
    require(!current.scheme.equals("https", true) || target.scheme.equals("https", true)) {
        "Subscription HTTPS downgrade blocked"
    }
    return target
}

internal fun URI.hasSameSubscriptionOrigin(other: URI): Boolean =
    subscriptionOriginOrNull()?.let { it == other.subscriptionOriginOrNull() } == true

internal fun URI.toSubscriptionRequestUrl(): URL {
    val url = toURL()
    val host = url.host
    val asciiHost = host.toAsciiSubscriptionHost()
    if (asciiHost == host) return url

    val authority = rawAuthority ?: return url
    val hostStart = authority.lastIndexOf('@') + 1
    val asciiAuthority = authority.replaceRange(hostStart, hostStart + host.length, asciiHost)
    return URI(toString().replaceFirst("://$authority", "://$asciiAuthority")).toURL()
}

private data class SubscriptionOrigin(val scheme: String, val host: String, val port: Int)

private fun URI.subscriptionOriginOrNull(): SubscriptionOrigin? {
    val scheme = scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    return runCatching {
        val url = toURL()
        val host = url.host.takeIf(String::isNotEmpty) ?: return null
        val port = url.port.takeUnless { it == -1 } ?: url.defaultPort
        SubscriptionOrigin(scheme, host.toAsciiSubscriptionHost().lowercase(), port)
    }.getOrNull()
}

private fun String.toAsciiSubscriptionHost(): String =
    if (':' in this) this else IDN.toASCII(this, IDN.ALLOW_UNASSIGNED)
