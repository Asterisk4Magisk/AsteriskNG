// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase

import features.proxy.server.usecase.importer.importPayloads
import features.proxy.server.usecase.importer.parseProxyServersFromPayloads
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal const val MaxProxyServerImportBytes = 256 * 1024 * 1024

internal suspend fun importProxyServersFromText(
    text: String,
    source: ProxyServerImportSource,
    providerUrlFetcher: ProxyServerProviderUrlFetcher? = null,
): ProxyServerImportResult {
    val context = ProxyServerImportContext(
        source = source,
        providerUrlFetcher = providerUrlFetcher,
    )
    return parseProxyServersFromPayloads(
        payloads = text.importPayloads(source),
        context = context,
    )
}

internal fun requireProxyServerImportSize(sizeBytes: Long) {
    require(sizeBytes <= MaxProxyServerImportBytes) { "Import content exceeds 256 MiB" }
}

internal fun InputStream.readProxyServerImportText(): String {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val output = ByteArrayOutputStream(buffer.size)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        requireProxyServerImportSize(output.size().toLong() + count)
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}
