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
            val parsedKey = key.toProxyAppSelectionKeyOrNull() ?: return@filter false
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
