// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.app.model

/**
 * One stored proxy app selection: `<userId>:<packageName>`, or a bare package name that
 * belongs to the current user. The selection list, the clipboard codec, the VPN policy and
 * the ROOT UID resolver all parse keys here so they agree on which entries are valid.
 */
internal data class ProxyAppSelectionKey(
    val userId: Int?,
    val packageName: String,
) {
    val normalizedKey: String
        get() = userId?.let { id -> "$id:$packageName" } ?: packageName

    fun userIdOr(defaultUserId: Int): Int = userId ?: defaultUserId
}

internal fun String.toProxyAppSelectionKeyOrNull(): ProxyAppSelectionKey? {
    val normalized = trim().takeIf(String::isNotEmpty) ?: return null
    val separatorIndex = normalized.indexOf(':')
    if (separatorIndex < 0) {
        return ProxyAppSelectionKey(
            userId = null,
            packageName = normalized,
        )
    }

    val userId = normalized.substring(0, separatorIndex).trim().toIntOrNull()
        ?: return null
    val packageName = normalized.substring(separatorIndex + 1).trim().takeIf(String::isNotEmpty)
        ?: return null
    return ProxyAppSelectionKey(
        userId = userId,
        packageName = packageName,
    )
}
