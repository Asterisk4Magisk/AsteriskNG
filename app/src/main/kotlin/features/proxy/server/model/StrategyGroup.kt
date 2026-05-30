// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.model

import kotlinx.serialization.Serializable

object StrategyGroupConstants {
    const val TYPE_LEAST_PING = "leastPing"
    const val TYPE_LEAST_LOAD = "leastLoad"
    const val TYPE_RANDOM = "random"
    const val TYPE_ROUND_ROBIN = "roundRobin"

    val TYPES = setOf(
        TYPE_LEAST_PING,
        TYPE_LEAST_LOAD,
        TYPE_RANDOM,
        TYPE_ROUND_ROBIN,
    )
}

@Serializable
data class StrategyGroup(
    var remarks: String = "",
    var strategy: String = StrategyGroupConstants.TYPE_LEAST_PING,
    var subscriptionGroupId: Int? = null,
    var filter: String = "",
) : ProxyServer<StrategyGroup> {
    override fun getInfo(): ProxyServerInfo {
        val source = subscriptionGroupId?.toString() ?: "all"
        val filterText = filter.takeIf(String::isNotBlank)?.let { ", $it" }.orEmpty()
        return ProxyServerInfo(remarks, "$strategy, $source$filterText", "Strategy")
    }

    override fun toXrayOutbound(tag: String): OutboundObject {
        throw UnsupportedOperationException("Strategy groups are converted by XrayConfigFactory")
    }

    override fun update(other: ProxyServer<*>) {
        if (other !is StrategyGroup) {
            proxyServerTypeMismatch()
        }
        remarks = other.remarks
        strategy = other.strategy
        subscriptionGroupId = other.subscriptionGroupId
        filter = other.filter
    }

    override fun check() {
        validateRemarks(remarks)
        validateAllowed(strategy, "strategy group type", StrategyGroupConstants.TYPES)
    }
}
