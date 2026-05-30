// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase

import features.proxy.server.usecase.importer.importPayloads
import features.proxy.server.usecase.importer.parseProxyServersFromJsonConfig
import features.proxy.server.usecase.importer.parseProxyServersFromMihomoYamlConfig
import features.proxy.server.usecase.importer.parseProxyServersFromUrls

internal fun importProxyServersFromText(
    text: String,
    source: ProxyServerImportSource,
): ProxyServerImportResult {
    return parseProxyServersFromPayloads(
        payloads = text.importPayloads(source),
        source = source,
    )
}

private fun parseProxyServersFromPayloads(
    payloads: List<String>,
    source: ProxyServerImportSource,
): ProxyServerImportResult {
    var lastAttempt = EmptyProxyServerImportResult
    for (payload in payloads) {
        for (parser in ProxyServerImportParsers) {
            val result = parser(payload, source)
            if (result.servers.isNotEmpty()) {
                return result
            }
            if (result.urlCount > 0 || lastAttempt.urlCount == 0) {
                lastAttempt = result
            }
        }
    }
    return lastAttempt
}

private val ProxyServerImportParsers: List<ProxyServerPayloadParser> = listOf(
    ::parseProxyServersFromJsonConfig,
    ::parseProxyServersFromMihomoYamlConfig,
    ::parseProxyServersFromUrls,
)
