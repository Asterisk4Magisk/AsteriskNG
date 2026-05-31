// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase.importer

import features.proxy.server.usecase.ProxyServerImportSource
import utils.decodeFlexibleBase64OrNull

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
    return normalized.decodeFlexibleBase64OrNull()?.decodeToString()
}

internal const val ImportByteOrderMark = '\uFEFF'
