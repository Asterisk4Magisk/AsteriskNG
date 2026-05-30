// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import app.AppState
import app.modes.ProxyAppListModeBlacklist
import app.modes.ProxyAppListModeGlobal
import app.modes.ProxyAppListModeWhitelist

internal data class VpnApplicationPolicy(
    val mode: Int = ProxyAppListModeGlobal,
    val packageNames: List<String> = emptyList(),
)

internal fun AppState.toVpnApplicationPolicy(currentUserId: Int): VpnApplicationPolicy {
    val mode = when (proxyAppListMode) {
        ProxyAppListModeBlacklist,
        ProxyAppListModeWhitelist,
        ProxyAppListModeGlobal -> proxyAppListMode

        else -> ProxyAppListModeGlobal
    }
    if (mode == ProxyAppListModeGlobal) {
        return VpnApplicationPolicy(mode = mode)
    }
    return VpnApplicationPolicy(
        mode = mode,
        packageNames = proxyAppListSelectedApps.toVpnServicePackageNames(currentUserId),
    )
}

private fun List<String>.toVpnServicePackageNames(currentUserId: Int): List<String> {
    return mapNotNull { key ->
        val separatorIndex = key.indexOf(':')
        if (separatorIndex < 0) {
            key
        } else {
            val userId = key.substring(0, separatorIndex).toIntOrNull()
            if (userId == currentUserId) {
                key.substring(separatorIndex + 1)
            } else {
                null
            }
        }
    }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
}
