// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase.importer

import features.logs.AndroidAppLogger
import features.proxy.server.model.ProxyServer
import features.proxy.server.model.ProxyServerConstants
import features.proxy.server.usecase.ProxyServerImportContext
import features.proxy.server.usecase.ProxyServerImportResult
import features.proxy.server.usecase.ProxyServerImportSource

internal suspend fun parseProxyServersFromUrls(
    text: String,
    context: ProxyServerImportContext,
): ProxyServerImportResult {
    val source = context.source
    val seenUrls = linkedSetOf<String>()
    val candidates = mutableListOf<ProxyUrlImportCandidate>()
    var urlCount = 0

    fun importCandidate(url: String): Boolean {
        if (!seenUrls.add(url)) return false
        if (url.startsWith(V2rayNSharePrefix, ignoreCase = true)) {
            val candidate = runCatching { parseV2rayNImportCandidate(url, urlCount) }.getOrElse {
                // Decoder exceptions can contain the private JSON payload.
                AndroidAppLogger.warn(ProxyServerImportLogTag, "Failed to import v2rayN share index=$urlCount source=${source.logName}")
                null
            }
            urlCount += 1
            candidate?.let(candidates::add)
            return candidate != null
        }
        val server = parseProxyServerUrlOrNull(
            url = url.withMihomoProviderUrlOverrides(context.mihomoProviderOptions),
            index = urlCount,
            source = source,
        )
        urlCount += 1
        server?.let { candidates += ProxyUrlImportCandidate(it) }
        return server != null
    }

    text.lineSequence().forEach { rawLine ->
        val line = rawLine.trim().trimStart(ImportByteOrderMark)
        if (line.isBlank()) return@forEach

        if (line.startsWithProxyServerScheme()) {
            if (importCandidate(line)) return@forEach
        }

        line.embeddedProxyServerUrls()
            .filterNot { url -> url == line }
            .forEach { url ->
                importCandidate(url)
            }
    }

    return ProxyServerImportResult(
        urlCount = urlCount,
        servers = resolveV2rayNImportCandidates(candidates)
            .applyMihomoProviderServerOptions(context.mihomoProviderOptions),
    )
}

private fun parseProxyServerUrlOrNull(
    url: String,
    index: Int,
    source: ProxyServerImportSource,
): ProxyServer<*>? {
    return runCatching {
        val server = ProxyServer.parse(url)
        val issues = server.validateBasic()
        if (issues.isNotEmpty()) {
            throw IllegalArgumentException("Basic validation failed: ${issues.joinToString { it.error.name }}")
        }
        server
    }
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

private fun String.embeddedProxyServerUrls(): Sequence<String> {
    return ProxyServerUrlRegex.findAll(this)
        .map { match -> match.value.trimEnd(',', ';') }
}

private fun String.startsWithProxyServerScheme(): Boolean {
    val lower = lowercase()
    return ProxyServerUrlPrefixes.any { prefix -> lower.startsWith(prefix) }
}

private val ProxyServerUrlPrefixes = listOf(
    V2rayNSharePrefix,
    "${ProxyServerConstants.PROTOCOL_HTTP}://",
    "${ProxyServerConstants.PROTOCOL_SOCKS}://",
    "${ProxyServerConstants.PROTOCOL_SOCKS4}://",
    "${ProxyServerConstants.PROTOCOL_SOCKS5}://",
    "${ProxyServerConstants.PROTOCOL_SS}://",
    "${ProxyServerConstants.PROTOCOL_VMESS}://",
    "${ProxyServerConstants.PROTOCOL_VLESS}://",
    "${ProxyServerConstants.PROTOCOL_TROJAN}://",
    "${ProxyServerConstants.PROTOCOL_HY2}://",
    "${ProxyServerConstants.PROTOCOL_HYSTERIA2}://",
    "${ProxyServerConstants.PROTOCOL_WIREGUARD}://",
)

private val ProxyServerUrlRegex = Regex(
    "(?i)\\b(?:http|socks|socks4|socks5|ss|vmess|vless|v2rayn|trojan|hy2|hysteria2|wireguard)://[^\\s<>\"']+",
)

private const val ProxyServerImportLogTag = "ProxyServerImport"
