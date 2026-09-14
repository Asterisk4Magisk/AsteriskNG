// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.app

/**
 * Blacklist mode: append matched entries so the corresponding apps bypass the proxy.
 * Existing selection order is preserved; new matches are appended in scan order.
 */
internal fun mergeSelectedAppsForScan(
    current: List<String>,
    matched: Collection<String>,
): List<String> {
    if (matched.isEmpty()) return current
    val existing = LinkedHashSet(current)
    matched.forEach(existing::add)
    return existing.toList()
}

/**
 * Whitelist mode: invert selection w.r.t. the full installed set minus matched.
 * Result = (allInstalledKeys - matched) so every non-Chinese app gets proxied
 * while Chinese apps fall back to the default-deny (direct) path.
 */
internal fun invertSelectionForScan(
    matched: Collection<String>,
    allKeys: Collection<String>,
): List<String> {
    val matchedSet = matched.toSet()
    return allKeys.filterNot { it in matchedSet }.distinct()
}


/** Packages sharing a UID must be selected together because routing is UID-based. */
internal fun expandSelectionToSharedUids(
    selected: Collection<String>,
    keyGroups: Collection<Collection<String>>,
): List<String> {
    val selectedSet = selected.toSet()
    return keyGroups.filter { group -> group.any { it in selectedSet } }.flatten().distinct()
}
