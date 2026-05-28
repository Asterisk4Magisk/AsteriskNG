package features.proxy.server.usecase.importer

import features.proxy.server.usecase.ProxyServerImportSource
import kotlin.io.encoding.Base64

internal fun String.importPayloads(source: ProxyServerImportSource): List<String> {
    return if (source.decodeBase64) {
        listOfNotNull(decodeImportBase64(), this).distinct()
    } else {
        listOf(this)
    }
}

private fun String.decodeImportBase64(): String? {
    val normalized = trimStart(ImportByteOrderMark).filterNot(Char::isWhitespace)
    if (normalized.isBlank()) return null
    return ImportBase64Decoders.firstNotNullOfOrNull { decoder ->
        runCatching { decoder.decode(normalized).decodeToString() }.getOrNull()
    } ?: normalized.trimEnd('=').takeIf { it.length != normalized.length }?.let { trimmed ->
        ImportBase64Decoders.firstNotNullOfOrNull { decoder ->
            runCatching { decoder.decode(trimmed).decodeToString() }.getOrNull()
        }
    }
}

private val ImportBase64Decoders = listOf(
    Base64.Default,
    Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL),
    Base64.UrlSafe,
    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL),
)

internal const val ImportByteOrderMark = '\uFEFF'
