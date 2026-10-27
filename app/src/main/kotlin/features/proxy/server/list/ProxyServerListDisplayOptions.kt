// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.list

import app.displayTitle
import app.ProxyServerState
import app.modes.ProxyServerListLayoutDouble
import app.modes.ProxyServerListLayoutMultiple
import app.modes.ProxyServerListSortLatency
import app.modes.ProxyServerListSortName

internal fun Int.resolvedProxyServerListColumns(): Int {
    return when (this) {
        ProxyServerListLayoutDouble -> 2
        ProxyServerListLayoutMultiple -> 3
        else -> 1
    }
}

internal fun List<ProxyServerState>.sortedForProxyServerList(
    sort: Int,
): List<ProxyServerState> {
    return when (sort) {
        ProxyServerListSortName -> sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { server -> server.displayTitle() },
        )
        ProxyServerListSortLatency -> sortedWith(
            compareBy<ProxyServerState> { server -> server.latency.proxyServerListLatencySortKey() }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { server -> server.displayTitle() },
        )
        else -> this
    }
}

private fun String.proxyServerListLatencySortKey(): Int {
    val number = latencyNumberRegex.find(this)?.value?.toIntOrNull()
    return when {
        number != null -> number
        isBlank() -> Int.MAX_VALUE
        else -> Int.MAX_VALUE - 1
    }
}

private val latencyNumberRegex = Regex("""\d+""")
