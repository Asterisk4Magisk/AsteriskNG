// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.model

import app.AppState
import app.ProxyServerState

/** The proxy servers a strategy group balances between, in list order. */
internal fun AppState.strategyGroupMembers(strategyGroup: StrategyGroup): List<ProxyServerState> {
    val regex = strategyGroup.filter.takeIf(String::isNotBlank)?.let { filter ->
        runCatching { Regex(filter) }.getOrNull()
    }
    return proxyServers
        .asSequence()
        .filter { server -> !server.server.isCompositeProxyServer() }
        .filter { server -> !server.server.isCustomProxyServer() }
        .filter { server ->
            strategyGroup.subscriptionGroupId == null || server.groupId == strategyGroup.subscriptionGroupId
        }
        .filter { server ->
            val filter = strategyGroup.filter
            filter.isBlank() ||
                regex?.containsMatchIn(server.server.getInfo().remarks) == true ||
                (regex == null && server.server.getInfo().remarks.contains(filter))
        }
        .toList()
}
