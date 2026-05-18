package data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.ProxyServerState
import app.SubscriptionGroupState
import features.logs.AndroidAppLogger
import features.routing.model.RouteRule

@Entity(
    tableName = "subscription_groups",
    indices = [Index("position")],
)
internal data class SubscriptionGroupEntity(
    @PrimaryKey val id: Int,
    val position: Int,
    val name: String,
    val url: String,
    val userAgent: String,
    val updateInterval: String,
    val updateViaProxy: Boolean,
    val enabled: Boolean,
    val builtIn: Boolean,
    val lastUpdatedAtMillis: Long,
) {
    fun toState(): SubscriptionGroupState {
        return SubscriptionGroupState(
            id = id,
            name = name,
            url = url,
            userAgent = userAgent,
            updateInterval = updateInterval,
            updateViaProxy = updateViaProxy,
            enabled = enabled,
            builtIn = builtIn,
            lastUpdatedAtMillis = lastUpdatedAtMillis,
        )
    }

    companion object {
        fun from(position: Int, group: SubscriptionGroupState): SubscriptionGroupEntity {
            return SubscriptionGroupEntity(
                id = group.id,
                position = position,
                name = group.name,
                url = group.url,
                userAgent = group.userAgent,
                updateInterval = group.updateInterval,
                updateViaProxy = group.updateViaProxy,
                enabled = group.enabled,
                builtIn = group.builtIn,
                lastUpdatedAtMillis = group.lastUpdatedAtMillis,
            )
        }
    }
}

@Entity(
    tableName = "proxy_servers",
    indices = [
        Index("groupId"),
        Index("position"),
    ],
)
internal data class ProxyServerEntity(
    @PrimaryKey val id: Int,
    val position: Int,
    val groupId: Int,
    val serverJson: String,
) {
    fun toState(): ProxyServerState? {
        return runCatching {
            ProxyServerState(
                id = id,
                server = serverJson.decodePersistedProxyServer(),
                groupId = groupId,
            )
        }.onFailure { error ->
            AndroidAppLogger.warn(LogTag, "Failed to parse persisted proxy server id=$id", error)
        }.getOrNull()
    }

    companion object {
        fun from(position: Int, server: ProxyServerState): ProxyServerEntity {
            return ProxyServerEntity(
                id = server.id,
                position = position,
                groupId = server.groupId,
                serverJson = server.server.encodePersistedProxyServer(),
            )
        }
    }
}

@Entity(
    tableName = "routing_rules",
    indices = [Index("position")],
)
internal data class RouteRuleEntity(
    @PrimaryKey val id: Int,
    val position: Int,
    val remarks: String,
    val outboundTag: String,
    val domainJson: String,
    val ipJson: String,
    val processJson: String,
    val port: String,
    val protocol: String,
    val network: String,
    val enabled: Boolean,
) {
    fun toState(): RouteRule {
        return RouteRule(
            id = id,
            remarks = remarks,
            outboundTag = outboundTag,
            domain = StringListJson.decode(domainJson),
            ip = StringListJson.decode(ipJson),
            process = StringListJson.decode(processJson),
            port = port,
            protocol = protocol,
            network = network,
            enabled = enabled,
        )
    }

    companion object {
        fun from(position: Int, rule: RouteRule): RouteRuleEntity {
            return RouteRuleEntity(
                id = rule.id,
                position = position,
                remarks = rule.remarks,
                outboundTag = rule.outboundTag,
                domainJson = StringListJson.encode(rule.domain),
                ipJson = StringListJson.encode(rule.ip),
                processJson = StringListJson.encode(rule.process),
                port = rule.port,
                protocol = rule.protocol,
                network = rule.network,
                enabled = rule.enabled,
            )
        }
    }
}

@Entity(
    tableName = "proxy_app_list_selected_apps",
    indices = [Index("position")],
)
internal data class ProxyAppListSelectedAppEntity(
    @PrimaryKey val packageKey: String,
    val position: Int,
)

internal fun List<ProxyServerState>.hasSamePersistedContent(other: List<ProxyServerState>): Boolean {
    return size == other.size && zip(other).all { (previous, next) ->
        previous.id == next.id &&
            previous.groupId == next.groupId &&
            previous.server.encodePersistedProxyServer() == next.server.encodePersistedProxyServer()
    }
}

private const val LogTag = "ListEntities"
