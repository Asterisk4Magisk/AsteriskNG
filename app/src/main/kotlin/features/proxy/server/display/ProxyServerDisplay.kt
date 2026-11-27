// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.display

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import features.subscription.DefaultSubscriptionGroupId
import app.ProxyServerState
import app.R
import app.SubscriptionGroupState
import features.proxy.server.model.StrategyGroupConstants
import ui.text.formatTemplate
import utils.nameFromSubscriptionUrl

@Composable
internal fun strategyGroupLabels(): Map<String, String> = mapOf(
    StrategyGroupConstants.TYPE_LEAST_PING to stringResource(R.string.proxy_editor_strategy_group_least_ping),
    StrategyGroupConstants.TYPE_LEAST_LOAD to stringResource(R.string.proxy_editor_strategy_group_least_load),
    StrategyGroupConstants.TYPE_RANDOM to stringResource(R.string.proxy_editor_strategy_group_random),
    StrategyGroupConstants.TYPE_ROUND_ROBIN to stringResource(R.string.proxy_editor_strategy_group_round_robin),
    StrategyGroupConstants.TYPE_FAILOVER to stringResource(R.string.proxy_editor_strategy_group_failover),
)

internal fun SubscriptionGroupState.displayName(defaultGroupName: String): String {
    return if (builtIn && id == DefaultSubscriptionGroupId) {
        defaultGroupName
    } else {
        // A blank name awaits auto-assignment on the first sync; show the host meanwhile.
        name.ifBlank { nameFromSubscriptionUrl(url).orEmpty() }
    }
}

internal fun List<SubscriptionGroupState>.displayNameById(defaultGroupName: String): Map<Int, String> {
    return associate { group ->
        group.id to group.displayName(defaultGroupName)
    }
}

internal fun ProxyServerState.displayNameWithGroup(
    defaultProxyServerTemplate: String,
    groupNames: Map<Int, String>,
    unknownGroupName: String,
): String {
    val proxyServerName = server.getInfo().remarks.ifBlank {
        defaultProxyServerTemplate.formatTemplate("id" to id)
    }
    if (groupId == DefaultSubscriptionGroupId) {
        return proxyServerName
    }
    val groupName = groupNames[groupId] ?: unknownGroupName
    return "$proxyServerName ($groupName)"
}
