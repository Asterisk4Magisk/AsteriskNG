// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.display

/**
 * Protocol label for proxy server cards. Compact (multi-column) cards use the same
 * abbreviations as AsteriskMETA and AsteriskBOX; other names are returned unchanged.
 */
internal fun String.displayProxyServerProtocolName(compact: Boolean = false): String {
    if (!compact) return this
    return when (trim().lowercase().replace("_", "-").replace(" ", "-")) {
        "ss", "shadowsocks" -> "SS"
        "hysteria2", "hy2" -> "HY2"
        "wireguard", "wire-guard", "wg" -> "WG"
        else -> this
    }
}
