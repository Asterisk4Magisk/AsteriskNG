// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package data

import app.AppState

internal data class PersistedAppState(
    val subscriptionGroups: List<SubscriptionGroupEntity>,
    val proxyServers: List<ProxyServerEntity>,
    val routingRules: List<RouteRuleEntity>,
    val proxyAppListSelectedApps: List<ProxyAppListSelectedAppEntity>,
) {
    fun hasRoomContent(): Boolean {
        return subscriptionGroups.isNotEmpty() ||
            proxyServers.isNotEmpty() ||
            routingRules.isNotEmpty() ||
            proxyAppListSelectedApps.isNotEmpty()
    }

    fun toAppState(settings: AppState): AppState {
        val restoredProxyServerList = proxyServers.mapNotNull { server -> server.toState() }
        val restoredSelectedProxyServerId = settings.selectedProxyServerId
            .takeIf { serverId -> restoredProxyServerList.any { server -> server.id == serverId } }
            ?: restoredProxyServerList.firstOrNull()?.id
            ?: settings.selectedProxyServerId

        return settings.copy(
            subscriptionGroups = subscriptionGroups.map { group -> group.toState() },
            proxyServers = restoredProxyServerList,
            selectedProxyServerId = restoredSelectedProxyServerId,
            proxyRunning = false,
            routeRules = routingRules.map { rule -> rule.toState() },
            proxyAppListSelectedApps = proxyAppListSelectedApps.map { app -> app.packageKey },
        )
    }
}
