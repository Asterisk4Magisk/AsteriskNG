// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.app.model

import utils.toTrimmedNonEmptyDistinctList

internal fun pruneProxyAppListSelectedApps(
    selectedApps: List<String>,
    installedApps: List<AppPackageEntry>,
    selfPackageName: String,
): List<String> {
    if (selectedApps.isEmpty()) {
        return selectedApps
    }

    val installedKeys = installedApps
        .map { app -> app.selectionKey() }
        .toSet()
    val installedPackageNames = installedApps
        .map { app -> app.packageName }
        .toSet()
    val loadedUserIds = installedApps
        .map { app -> app.userId ?: 0 }
        .toSet()

    return selectedApps
        .toTrimmedNonEmptyDistinctList()
        .filter { key ->
            val parsedKey = key.toProxyAppSelectionKey() ?: return@filter false
            if (parsedKey.packageName == selfPackageName) {
                return@filter false
            }
            if (parsedKey.userId == null) {
                return@filter parsedKey.packageName in installedPackageNames
            }
            if (parsedKey.userId !in loadedUserIds) {
                return@filter true
            }
            parsedKey.normalizedKey in installedKeys
        }
}

private data class ProxyAppSelectionKey(
    val userId: Int?,
    val packageName: String,
) {
    val normalizedKey: String
        get() = userId?.let { id -> "$id:$packageName" } ?: packageName
}

private fun String.toProxyAppSelectionKey(): ProxyAppSelectionKey? {
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
