// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.app.usecase

import app.modes.ProxyAppListModeBlacklist
import app.modes.ProxyAppListModeGlobal
import app.modes.ProxyAppListModeWhitelist
import ui.clipboard.ClipboardImportException
import ui.clipboard.ClipboardImportFailure
import ui.clipboard.ClipboardImportMode
import utils.toTrimmedNonEmptyDistinctList

internal data class ProxyAppListClipboardData(
    val mode: Int?,
    val selectedApps: List<String>,
)

internal data class ProxyAppListClipboardApplyResult(
    val mode: Int,
    val selectedApps: List<String>,
)

internal fun encodeProxyAppListForClipboard(
    selectedApps: List<String>,
    mode: Int,
): String {
    return (listOf("mode=${mode.toClipboardModeName()}") + selectedApps.toTrimmedNonEmptyDistinctList())
        .joinToString("\n")
}

internal fun decodeProxyAppListFromClipboard(
    text: String,
    currentUserId: Int,
    selfPackageName: String,
): ProxyAppListClipboardData {
    val lines = text.trimStart(ImportByteOrderMark)
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toList()
    if (lines.isEmpty()) {
        throw ClipboardImportException(ClipboardImportFailure.EmptyClipboard)
    }

    val firstLine = lines.first()
    val mode = firstLine.toClipboardModeOrNull()
    val appLines = if (mode != null || firstLine.isV2RayModeLine()) {
        lines.drop(1)
    } else {
        lines
    }
    val selectedApps = appLines.map { line ->
        line.toSelectionKey(currentUserId = currentUserId)
    }.filterNot { key ->
        key.substringAfter(':') == selfPackageName
    }.toTrimmedNonEmptyDistinctList()

    if (selectedApps.isEmpty()) {
        throw ClipboardImportException(ClipboardImportFailure.NoValidApps)
    }
    return ProxyAppListClipboardData(
        mode = mode,
        selectedApps = selectedApps,
    )
}

internal fun applyProxyAppListClipboardImport(
    currentMode: Int,
    currentSelectedApps: List<String>,
    imported: ProxyAppListClipboardData,
    mode: ClipboardImportMode,
): ProxyAppListClipboardApplyResult {
    val nextMode = imported.mode ?: currentMode
    val selectedApps = when (mode) {
        ClipboardImportMode.Replace -> imported.selectedApps
        ClipboardImportMode.Merge -> (currentSelectedApps + imported.selectedApps)
            .toTrimmedNonEmptyDistinctList()
    }
    return ProxyAppListClipboardApplyResult(
        mode = nextMode,
        selectedApps = selectedApps,
    )
}

private fun Int.toClipboardModeName(): String {
    return when (this) {
        ProxyAppListModeBlacklist -> "blacklist"
        ProxyAppListModeWhitelist -> "whitelist"
        ProxyAppListModeGlobal -> "global"
        else -> "global"
    }
}

private fun String.toClipboardModeOrNull(): Int? {
    val normalized = trim().lowercase()
    if (normalized == "true") return ProxyAppListModeBlacklist
    if (normalized == "false") return ProxyAppListModeWhitelist
    if (!normalized.startsWith("mode=")) return null
    return when (normalized.substringAfter("mode=").trim()) {
        "blacklist" -> ProxyAppListModeBlacklist
        "whitelist" -> ProxyAppListModeWhitelist
        "global" -> ProxyAppListModeGlobal
        else -> throw ClipboardImportException(ClipboardImportFailure.UnsupportedAppMode)
    }
}

private fun String.isV2RayModeLine(): Boolean {
    val normalized = trim().lowercase()
    return normalized == "true" || normalized == "false"
}

private fun String.toSelectionKey(currentUserId: Int): String {
    if (any(Char::isWhitespace)) {
        throw ClipboardImportException(ClipboardImportFailure.InvalidAppEntry)
    }
    val separatorIndex = indexOf(':')
    if (separatorIndex < 0) {
        return "$currentUserId:$this"
    }
    val userId = substring(0, separatorIndex).toIntOrNull()
        ?: throw ClipboardImportException(ClipboardImportFailure.InvalidAppUserId)
    val packageName = substring(separatorIndex + 1)
    if (packageName.isBlank()) {
        throw ClipboardImportException(ClipboardImportFailure.InvalidAppEntry)
    }
    return "$userId:$packageName"
}

private const val ImportByteOrderMark = '\uFEFF'
