package features.proxy.server.usecase

import app.AppState
import app.ProxyServerState
import app.SubscriptionGroupState
import features.proxy.server.list.ProxyServerListAddAction
import features.proxy.server.model.ChainProxy
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
import kotlin.io.encoding.Base64

internal data class ProxyServerListImportResult(
    val urlCount: Int,
    val servers: List<ProxyServerState>,
)

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

internal fun AppState.withImportedProxyServers(
    importResult: ProxyServerListImportResult,
): AppState {
    if (importResult.servers.isEmpty()) {
        return this
    }
    return copy(
        proxyServers = importResult.servers + proxyServers,
        nextProxyServerId = maxOf(
            nextProxyServerId,
            (importResult.servers.maxOfOrNull { server -> server.id } ?: 0) + 1,
        ),
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
    val nextServers = importedServers + proxyServers.filterNot { server -> server.groupId in updatedGroupIds }
    val selectedServerId = when {
        nextServers.any { server -> server.id == selectedProxyServerId } -> selectedProxyServerId
        else -> proxyServers.firstOrNull { server -> server.groupId !in updatedGroupIds }?.id
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
        group.enabled && !group.builtIn && group.url.isNotBlank()
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

internal fun createProxyServer(action: ProxyServerListAddAction): ProxyServer<*> {
    return when (action) {
        ProxyServerListAddAction.ScanQrCode,
        ProxyServerListAddAction.Clipboard,
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
    }
}

internal fun importProxyServersFromText(
    text: String,
    startServerId: Int,
    groupId: Int,
): ProxyServerListImportResult {
    return importProxyServersFromLines(
        lines = text.lineSequence(),
        startServerId = startServerId,
        groupId = groupId,
    )
}

internal fun importProxyServersFromSubscriptionText(
    text: String,
    groupId: Int,
): ProxyServerListSubscriptionUpdate {
    val decodedText = text.decodeSubscriptionBase64()
    if (!decodedText.isNullOrBlank()) {
        val decodedResult = parseProxyServersFromLines(
            lines = decodedText.lineSequence(),
            distinct = true,
        )
        if (decodedResult.servers.isNotEmpty()) {
            return ProxyServerListSubscriptionUpdate(
                groupId = groupId,
                urlCount = decodedResult.urlCount,
                servers = decodedResult.servers,
            )
        }
    }
    val plainResult = parseProxyServersFromLines(
        lines = text.lineSequence(),
        distinct = true,
    )
    return ProxyServerListSubscriptionUpdate(
        groupId = groupId,
        urlCount = plainResult.urlCount,
        servers = plainResult.servers,
    )
}

private fun importProxyServersFromLines(
    lines: Sequence<String>,
    startServerId: Int,
    groupId: Int,
    distinct: Boolean = false,
): ProxyServerListImportResult {
    val urls = lines
        .map(String::trim)
        .filter(String::isNotEmpty)
        .let { sequence -> if (distinct) sequence.distinct() else sequence }
        .toList()
    var nextServerId = startServerId
    val servers = urls.mapNotNull { url ->
        runCatching {
            val server = ProxyServer.parse(url)
            ProxyServerState(
                id = nextServerId++,
                groupId = groupId,
                server = server,
            )
        }.getOrNull()
    }
    return ProxyServerListImportResult(
        urlCount = urls.size,
        servers = servers,
    )
}

private data class ParsedProxyServerLines(
    val urlCount: Int,
    val servers: List<ProxyServer<*>>,
)

private fun parseProxyServersFromLines(
    lines: Sequence<String>,
    distinct: Boolean = false,
): ParsedProxyServerLines {
    val urls = lines
        .map(String::trim)
        .filter(String::isNotEmpty)
        .let { sequence -> if (distinct) sequence.distinct() else sequence }
        .toList()
    val servers = urls.mapNotNull { url ->
        runCatching { ProxyServer.parse(url) }.getOrNull()
    }
    return ParsedProxyServerLines(
        urlCount = urls.size,
        servers = servers,
    )
}

private fun String.decodeSubscriptionBase64(): String? {
    val normalized = filterNot(Char::isWhitespace)
    if (normalized.isBlank()) return null
    return SubscriptionBase64Decoders.firstNotNullOfOrNull { decoder ->
        runCatching { decoder.decode(normalized).decodeToString() }.getOrNull()
    } ?: normalized.trimEnd('=').takeIf { it.length != normalized.length }?.let { trimmed ->
        SubscriptionBase64Decoders.firstNotNullOfOrNull { decoder ->
            runCatching { decoder.decode(trimmed).decodeToString() }.getOrNull()
        }
    }
}

private val SubscriptionBase64Decoders = listOf(
    Base64.Default,
    Base64.Default.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL),
    Base64.UrlSafe,
    Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL),
)

private const val MillisPerHour = 60L * 60L * 1000L
