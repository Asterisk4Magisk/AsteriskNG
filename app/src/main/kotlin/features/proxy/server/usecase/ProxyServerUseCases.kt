// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase

import app.AppState
import app.ProxyServerState
import app.SubscriptionGroupState
import features.logs.AndroidAppLogger
import features.proxy.server.list.ProxyServerListAddAction
import features.proxy.server.model.ChainProxy
import features.proxy.server.model.Custom
import features.proxy.server.model.HTTP
import features.proxy.server.model.Hysteria2
import features.proxy.server.model.Masque
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
import features.proxy.server.usecase.importer.ImportedSubscriptionGroup
import features.proxy.server.usecase.importer.ImportedExplicitGroup
import ui.text.formatTemplate
import utils.resolveSubscriptionName

internal data class ProxyServerListSubscriptionUpdate(
    val groupId: Int,
    val sourceIdentity: SubscriptionGroupFetchIdentity,
    val urlCount: Int,
    val servers: List<ProxyServer<*>>,
    // Decoded `Content-Disposition` filename of the subscription response.
    val remoteName: String? = null,
)

internal data class SubscriptionGroupFetchIdentity(
    val url: String,
    val userAgent: String,
    val updateInterval: String,
    val hwid: String,
    val ageSecretKey: String,
    val updateViaProxy: Boolean,
    val enabled: Boolean,
)

internal fun SubscriptionGroupState.subscriptionFetchIdentity(): SubscriptionGroupFetchIdentity {
    return SubscriptionGroupFetchIdentity(
        url = url,
        userAgent = userAgent,
        updateInterval = updateInterval,
        hwid = hwid,
        ageSecretKey = ageSecretKey,
        updateViaProxy = updateViaProxy,
        enabled = enabled,
    )
}

internal data class ProxyServerListSubscriptionFailure(
    val groupId: Int,
    val error: Throwable,
)

internal data class ProxyServerListSubscriptionUpdateResult(
    val updates: List<ProxyServerListSubscriptionUpdate>,
    val failures: List<ProxyServerListSubscriptionFailure>,
    val updatedAtMillis: Long,
) {
    val updatedGroupCount: Int = updates.size
    val failedGroupCount: Int = failures.size
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
    val assignedIds = importResult.servers.map { nextServerId++ }
    val importedServers = importResult.servers.mapIndexedNotNull { index, server ->
        if (server is StrategyGroup && server.subscriptionGroupId == ImportedExplicitGroup) {
            val memberNames = Regex(server.filter)
            val conflicts = proxyServers.any { existing ->
                existing.groupId == groupId && !existing.server.isCompositeProxyServer() &&
                    existing.server !is Custom && memberNames.containsMatchIn(existing.server.getInfo().remarks)
            }
            if (conflicts) {
                AndroidAppLogger.warn("ProxyServerImport", "Skipped explicit v2rayN strategy group with ambiguous existing members")
                return@mapIndexedNotNull null
            }
        }
        ProxyServerState(
            id = assignedIds[index],
            groupId = groupId,
            server = server.resolveImportedReferences(assignedIds, groupId, subscriptionManaged = false),
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

/**
 * Apply fetched subscription servers. A group whose name is still blank is
 * auto-named from the response (see utils.SubscriptionNaming, shared with
 * AsteriskBOX); [duplicateNameTemplate] builds the `{name}({ordinal})`
 * collision suffix. A non-blank name is never changed.
 */
internal fun AppState.withUpdatedSubscriptionServers(
    updates: List<ProxyServerListSubscriptionUpdate>,
    updatedAtMillis: Long,
    duplicateNameTemplate: String,
): AppState {
    val applicableUpdates = updates.filter { update ->
        subscriptionGroups.any { group ->
            group.id == update.groupId &&
                group.subscriptionFetchIdentity() == update.sourceIdentity
        }
    }
    if (applicableUpdates.isEmpty()) {
        return this
    }
    val updatedGroupIds = applicableUpdates.map { update -> update.groupId }.toSet()
    val usedIds = proxyServers.mapTo(mutableSetOf()) { server -> server.id }
    var nextServerId = nextProxyServerId.coerceAtLeast(1)
    val importedServers = applicableUpdates.flatMap { update ->
        val previousGroup = proxyServers.filter { server ->
            server.groupId == update.groupId && server.server.isSubscriptionReplaceable()
        }
        fun assignId(reusableId: Int?): Int {
            val id = reusableId ?: run {
                while (nextServerId in usedIds) nextServerId++
                nextServerId++
            }
            usedIds += id
            return id
        }
        val assignedIds = MutableList(update.servers.size) { 0 }
        val nodeIndexes = update.servers.indices.filter { !update.servers[it].isCompositeProxyServer() }
        val nodeMatches = matchImportedProxyServers(
            previousGroup.filter { !it.server.isCompositeProxyServer() },
            nodeIndexes.map(update.servers::get),
        )
        nodeIndexes.forEachIndexed { matchIndex, sourceIndex ->
            assignedIds[sourceIndex] = assignId(nodeMatches[matchIndex])
        }
        // Resolve child IDs before identity matching so duplicate-named downloaded
        // chains and groups can still retain their IDs and the selected server.
        val compositeIndexes = update.servers.indices.filter { update.servers[it].isCompositeProxyServer() }
        val compositeMatches = matchImportedProxyServers(
            previousGroup.filter { it.server.isCompositeProxyServer() },
            compositeIndexes.map { index ->
                update.servers[index].resolveImportedReferences(assignedIds, update.groupId, subscriptionManaged = true)
            },
        )
        compositeIndexes.forEachIndexed { matchIndex, sourceIndex ->
            assignedIds[sourceIndex] = assignId(compositeMatches[matchIndex])
        }
        update.servers.mapIndexed { index, server ->
            ProxyServerState(
                id = assignedIds[index],
                groupId = update.groupId,
                server = server.resolveImportedReferences(assignedIds, update.groupId, subscriptionManaged = true),
            )
        }
    }
    // Preserve user-created composite servers; downloaded groups and chains follow
    // their subscription snapshot, including removed entries and rewritten member IDs.
    val nextServers = importedServers + proxyServers.filterNot { server ->
        server.groupId in updatedGroupIds && server.server.isSubscriptionReplaceable()
    }
    val selectedServerId = when {
        nextServers.any { server -> server.id == selectedProxyServerId } -> selectedProxyServerId
        else -> proxyServers.firstOrNull { server -> server.groupId !in updatedGroupIds }?.id
            ?: nextServers.firstOrNull()?.id
            ?: selectedProxyServerId
    }
    val remoteNamesByGroupId = applicableUpdates.associate { update -> update.groupId to update.remoteName }
    val takenNames = subscriptionGroups.mapTo(mutableListOf()) { group -> group.name }
    return copy(
        subscriptionGroups = subscriptionGroups.map { group ->
            if (group.id in updatedGroupIds) {
                val resolvedName = group.name.takeIf(String::isBlank)?.let {
                    resolveSubscriptionName(
                        preferredName = remoteNamesByGroupId[group.id],
                        subscriptionUrl = group.url,
                        takenNames = takenNames,
                        formatDuplicate = { name, ordinal ->
                            duplicateNameTemplate.formatTemplate("name" to name, "ordinal" to ordinal)
                        },
                    )
                }?.also(takenNames::add)
                group.copy(
                    name = resolvedName ?: group.name,
                    lastUpdatedAtMillis = updatedAtMillis,
                )
            } else {
                group
            }
        },
        proxyServers = nextServers,
        nextProxyServerId = maxOf(nextProxyServerId, nextServerId),
        selectedProxyServerId = selectedServerId,
    )
}

private fun ProxyServer<*>.isSubscriptionReplaceable(): Boolean = when (this) {
    is ChainProxy -> subscriptionManaged
    is StrategyGroup -> subscriptionManaged
    else -> true
}

private fun ProxyServer<*>.resolveImportedReferences(
    assignedIds: List<Int>,
    groupId: Int,
    subscriptionManaged: Boolean,
): ProxyServer<*> = when (this) {
    is ChainProxy -> copy(
        proxyServerIds = proxyServerIds.map { id ->
            if (id < 0) assignedIds.getOrNull(-id - 1)?.takeIf { it > 0 }
                ?: error("Missing imported chain member") else id
        },
        subscriptionManaged = subscriptionManaged,
    )
    is StrategyGroup -> copy(
        subscriptionGroupId = if (subscriptionGroupId == ImportedSubscriptionGroup || subscriptionGroupId == ImportedExplicitGroup) {
            groupId
        } else subscriptionGroupId,
        subscriptionManaged = subscriptionManaged,
    )
    else -> this
}

internal fun List<SubscriptionGroupState>.updatableSubscriptionGroups(): List<SubscriptionGroupState> {
    return filter { group ->
        group.enabled && group.url.isNotBlank()
    }
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

        ProxyServerListAddAction.Masque -> Masque(port = "")

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
