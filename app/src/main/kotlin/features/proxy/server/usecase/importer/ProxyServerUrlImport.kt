// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase.importer

import features.logs.AndroidAppLogger
import features.proxy.server.model.ProxyServer
import features.proxy.server.model.ProxyServerConstants
import features.proxy.server.usecase.ProxyServerImportResult
import features.proxy.server.usecase.ProxyServerImportSource

internal fun parseProxyServersFromUrls(
    text: String,
    source: ProxyServerImportSource,
): ProxyServerImportResult {
    val urls = text.lineSequence()
        .proxyServerUrlCandidates(distinct = true)
    val servers = urls.mapIndexedNotNull { index, url ->
        parseProxyServerUrlOrNull(
            url = url,
            index = index,
            source = source,
        )
    }
    return ProxyServerImportResult(
        urlCount = urls.size,
        servers = servers,
    )
}

private fun parseProxyServerUrlOrNull(
    url: String,
    index: Int,
    source: ProxyServerImportSource,
): ProxyServer<*>? {
    return runCatching { ProxyServer.parse(url) }
        .onFailure { error ->
            AndroidAppLogger.warn(
                ProxyServerImportLogTag,
                url.importFailureMessage(index = index, source = source),
                error,
            )
        }
        .getOrNull()
}

private fun String.importFailureMessage(
    index: Int,
    source: ProxyServerImportSource,
): String {
    val protocol = substringBefore("://", missingDelimiterValue = "").ifBlank { "<blank>" }
    return "Failed to import proxy server URL source=${source.logName} index=$index protocol=$protocol length=$length"
}

private fun Sequence<String>.proxyServerUrlCandidates(distinct: Boolean): List<String> {
    val urls = flatMap { line -> line.proxyServerUrlCandidates() }
        .let { sequence -> if (distinct) sequence.distinct() else sequence }
    return urls.toList()
}

private fun String.proxyServerUrlCandidates(): Sequence<String> {
    val line = trim().trimStart(ImportByteOrderMark)
    if (line.isBlank()) return emptySequence()
    val embeddedUrls = ProxyServerUrlRegex.findAll(line)
        .map { match -> match.value.trimEnd(',', ';') }
        .filterNot { url -> url == line }
        .toList()
    return if (line.startsWithProxyServerScheme()) {
        (listOf(line) + embeddedUrls).asSequence()
    } else {
        embeddedUrls.asSequence()
    }
}

private fun String.startsWithProxyServerScheme(): Boolean {
    val lower = lowercase()
    return ProxyServerUrlPrefixes.any { prefix -> lower.startsWith(prefix) }
}

private val ProxyServerUrlPrefixes = listOf(
    "${ProxyServerConstants.PROTOCOL_HTTP}://",
    "${ProxyServerConstants.PROTOCOL_SOCKS}://",
    "${ProxyServerConstants.PROTOCOL_SS}://",
    "${ProxyServerConstants.PROTOCOL_VMESS}://",
    "${ProxyServerConstants.PROTOCOL_VLESS}://",
    "${ProxyServerConstants.PROTOCOL_TROJAN}://",
    "${ProxyServerConstants.PROTOCOL_HY2}://",
    "${ProxyServerConstants.PROTOCOL_HYSTERIA2}://",
    "${ProxyServerConstants.PROTOCOL_WIREGUARD}://",
)

private val ProxyServerUrlRegex = Regex(
    "(?i)\\b(?:http|socks|ss|vmess|vless|trojan|hy2|hysteria2|wireguard)://[^\\s<>\"']+",
)

private const val ProxyServerImportLogTag = "ProxyServerImport"
