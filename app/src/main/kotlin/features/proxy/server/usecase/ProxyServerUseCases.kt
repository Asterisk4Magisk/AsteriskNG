// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase

import app.AppState
import app.ProxyServerState
import app.SubscriptionGroupState
import features.proxy.server.list.ProxyServerListAddAction
import features.proxy.server.model.ChainProxy
import features.proxy.server.model.Custom
import features.proxy.server.model.HTTP
import features.proxy.server.model.Hysteria2
import features.proxy.server.model.ProxyServer
import features.proxy.server.model.Shadowsocks
import features.proxy.server.model.Socks
import features.proxy.server.model.StrategyGroup
import features.proxy.server.model.Trojan
import features.proxy.server.model.VLESS
import features.proxy.server.model.VMess
import features.proxy.server.model.Wireguard
import features.proxy.server.model.getUrlOrNull
import features.proxy.server.model.isCompositeProxyServer

internal data class ProxyServerListSubscriptionUpdate(
    val groupId: Int,
    val urlCount: Int,
    val servers: List<ProxyServer<*>>,
)

internal data class ProxyServerListSubscriptionUpdateResult(
    val updates: List<ProxyServerListSubscriptionUpdate>,
    val failedGroupCount: Int,
    val updatedAtMillis: Long,
) {
    val updatedGroupCount: Int = updates.size
    val importedServerCount: Int = updates.sumOf { update -> update.servers.size }
}

internal data class ProxyServerListDuplicateDeleteResult(
    val servers: List<ProxyServerState>,
    val removedCount: Int,
)

internal data class ProxyServerListInvalidDeleteResult(
    val servers: List<ProxyServerState>,
    val removedCount: Int,
    val removedServerIds: Set<Int>,
)

internal fun AppState.withImportedProxyServers(
    importResult: ProxyServerImportResult,
    groupId: Int,
): AppState {
    if (importResult.servers.isEmpty()) {
        return this
    }
    var nextServerId = nextProxyServerId
    val importedServers = importResult.servers.map { server ->
        ProxyServerState(
            id = nextServerId++,
            groupId = groupId,
            server = server,
        )
    }
    val nextServers = importedServers + proxyServers
    return copy(
        proxyServers = nextServers,
        nextProxyServerId = maxOf(nextProxyServerId, nextServerId),
        selectedProxyServerId = selectedProxyServerIdOrFirstAvailable(nextServers),
    )
}

internal data class ProxyServerEditApplyResult(
    val state: AppState,
    val existingGroupId: Int?,
    val wasExisting: Boolean,
)

internal fun AppState.withSavedProxyServer(
    serverId: Int,
    server: ProxyServer<*>,
    groupId: Int?,
): ProxyServerEditApplyResult {
    val index = proxyServers.indexOfFirst { it.id == serverId }
    val wasExisting = index >= 0
    var existingGroupId = groupId
    val nextServers = if (index >= 0) {
        proxyServers.toMutableList().also { list ->
            val oldServer = list[index]
            existingGroupId = oldServer.groupId
            list[index] = oldServer.copy(server = server)
        }
    } else if (groupId != null) {
        listOf(
            ProxyServerState(
                id = serverId,
                groupId = groupId,
                server = server,
            ),
        ) + proxyServers
    } else {
        proxyServers
    }
    return ProxyServerEditApplyResult(
        state = copy(
            proxyServers = nextServers,
            nextProxyServerId = maxOf(nextProxyServerId, serverId + 1),
            selectedProxyServerId = selectedProxyServerIdOrFirstAvailable(nextServers),
        ),
        existingGroupId = existingGroupId,
        wasExisting = wasExisting,
    )
}

internal fun AppState.withUpdatedSubscriptionServers(
    updates: List<ProxyServerListSubscriptionUpdate>,
    updatedAtMillis: Long,
): AppState {
    if (updates.isEmpty()) {
        return this
    }
    val updatedGroupIds = updates.map { update -> update.groupId }.toSet()
    var nextServerId = nextProxyServerId
    val importedServers = updates.flatMap { update ->
        update.servers.map { server ->
            ProxyServerState(
                id = nextServerId++,
                groupId = update.groupId,
                server = server,
            )
        }
    }
    // Preserve composite proxy servers (strategy groups, chain proxies) even when
    // their groupId matches an updated subscription group — they are user-created,
    // not downloaded from the subscription, and should survive subscription updates.
    val nextServers = importedServers + proxyServers.filterNot { server ->
        server.groupId in updatedGroupIds && !server.server.isCompositeProxyServer()
    }
    val selectedServerId = when {
        nextServers.any { server -> server.id == selectedProxyServerId } -> selectedProxyServerId
        else -> proxyServers.firstOrNull { server -> server.groupId !in updatedGroupIds }?.id
            ?: nextServers.firstOrNull()?.id
            ?: selectedProxyServerId
    }
    return copy(
        subscriptionGroups = subscriptionGroups.map { group ->
            if (group.id in updatedGroupIds) {
                group.copy(lastUpdatedAtMillis = updatedAtMillis)
            } else {
                group
            }
        },
        proxyServers = nextServers,
        nextProxyServerId = maxOf(nextProxyServerId, nextServerId),
        selectedProxyServerId = selectedServerId,
    )
}

internal fun List<SubscriptionGroupState>.updatableSubscriptionGroups(): List<SubscriptionGroupState> {
    return filter { group ->
        group.enabled && group.url.isNotBlank()
    }
}

internal fun List<SubscriptionGroupState>.dueSubscriptionGroups(nowMillis: Long): List<SubscriptionGroupState> {
    return updatableSubscriptionGroups().filter { group ->
        val intervalHours = group.updateIntervalHours() ?: return@filter false
        group.lastUpdatedAtMillis <= 0L ||
            nowMillis - group.lastUpdatedAtMillis >= intervalHours * MillisPerHour
    }
}

internal fun SubscriptionGroupState.updateIntervalHours(): Long? {
    return updateInterval.trim()
        .takeIf(String::isNotBlank)
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
}

internal fun List<ProxyServerState>.deleteDuplicateServersInGroup(
    currentGroupServerIds: Set<Int>,
    selectedProxyServerId: Int,
): ProxyServerListDuplicateDeleteResult {
    val keptServerIdsByUrl = mutableMapOf<String, Int>()
    val duplicateServerIds = mutableSetOf<Int>()
    forEach { server ->
        val url = runCatching { server.server.getUrlOrNull() }.getOrNull()
        if (server.id in currentGroupServerIds && url != null) {
            val keptServerId = keptServerIdsByUrl[url]
            if (keptServerId == null) {
                keptServerIdsByUrl[url] = server.id
            } else if (server.id == selectedProxyServerId) {
                duplicateServerIds += keptServerId
                keptServerIdsByUrl[url] = server.id
            } else {
                duplicateServerIds += server.id
            }
        }
    }

    return ProxyServerListDuplicateDeleteResult(
        servers = if (duplicateServerIds.isEmpty()) {
            this
        } else {
            filterNot { server -> server.id in duplicateServerIds }
        },
        removedCount = duplicateServerIds.size,
    )
}

internal fun List<ProxyServerState>.deleteInvalidServersInGroup(
    currentGroupServerIds: Set<Int>,
): ProxyServerListInvalidDeleteResult {
    val invalidServerIds = asSequence()
        .filter { server -> server.id in currentGroupServerIds }
        .filter { server -> server.server.validateFull().isNotEmpty() }
        .map { server -> server.id }
        .toSet()

    return ProxyServerListInvalidDeleteResult(
        servers = if (invalidServerIds.isEmpty()) {
            this
        } else {
            filterNot { server -> server.id in invalidServerIds }
        },
        removedCount = invalidServerIds.size,
        removedServerIds = invalidServerIds,
    )
}

internal fun AppState.withDeletedProxyServers(deletedServerIds: Set<Int>): AppState {
    if (deletedServerIds.isEmpty()) return this
    val nextServers = proxyServers.filterNot { server -> server.id in deletedServerIds }
    val selectedServerDeleted = selectedProxyServerId in deletedServerIds
    return copy(
        proxyServers = nextServers,
        selectedProxyServerId = if (selectedServerDeleted) {
            nextServers.firstOrNull()?.id ?: selectedProxyServerId
        } else {
            selectedProxyServerId
        },
        proxyRunning = proxyRunning && !selectedServerDeleted,
    )
}

internal fun createProxyServer(action: ProxyServerListAddAction): ProxyServer<*> {
    return when (action) {
        ProxyServerListAddAction.ScanQrCode,
        ProxyServerListAddAction.Clipboard,
        ProxyServerListAddAction.File -> error("Import action cannot create a proxy server")

        ProxyServerListAddAction.Shadowsocks -> Shadowsocks(port = "")

        ProxyServerListAddAction.ChainProxy -> ChainProxy()

        ProxyServerListAddAction.StrategyGroup -> StrategyGroup()

        ProxyServerListAddAction.HTTP -> HTTP(port = "")

        ProxyServerListAddAction.VMess -> VMess(port = "")

        ProxyServerListAddAction.VLESS -> VLESS()

        ProxyServerListAddAction.Trojan -> Trojan(port = "")

        ProxyServerListAddAction.Socks -> Socks(port = "")

        ProxyServerListAddAction.Hysteria2 -> Hysteria2(port = "")

        ProxyServerListAddAction.Wireguard -> Wireguard(port = "", reserved = "", address = "", mtu = "")

        ProxyServerListAddAction.Custom -> Custom()
    }
}

private fun AppState.selectedProxyServerIdOrFirstAvailable(nextServers: List<ProxyServerState>): Int {
    return if (nextServers.any { server -> server.id == selectedProxyServerId }) {
        selectedProxyServerId
    } else {
        nextServers.firstOrNull()?.id ?: selectedProxyServerId
    }
}

private const val MillisPerHour = 60L * 60L * 1000L
