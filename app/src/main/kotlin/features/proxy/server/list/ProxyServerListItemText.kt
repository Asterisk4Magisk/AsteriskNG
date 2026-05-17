package features.proxy.server.list

import app.ProxyServerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.R
import features.proxy.server.model.ChainProxy
import features.proxy.server.model.StrategyGroup
import features.proxy.server.model.StrategyGroupConstants
import androidx.compose.ui.res.stringResource
import ui.text.formatTemplate

internal data class ProxyServerListItemDisplayText(
    val title: String,
    val summary: String,
    val protocol: String,
)

internal class ProxyServerListItemTextFormatter(
    private val groupNames: Map<Int, String>,
    private val unknownGroupName: String,
    private val allGroupsName: String,
    private val leastPingName: String,
    private val leastLoadName: String,
    private val randomName: String,
    private val roundRobinName: String,
    private val strategyGroupSummaryTemplate: String,
    private val strategyGroupSummaryWithFilterTemplate: String,
    private val chainProxySummaryTemplate: String,
) {
    fun displayOf(serverState: ProxyServerState): ProxyServerListItemDisplayText {
        val info = serverState.server.getInfo()
        return ProxyServerListItemDisplayText(
            title = info.remarks.ifBlank { info.protocol },
            summary = summaryOf(serverState),
            protocol = info.protocol,
        )
    }

    private fun summaryOf(serverState: ProxyServerState): String {
        return when (val proxyServer = serverState.server) {
            is StrategyGroup -> proxyServer.strategyGroupSummary()
            is ChainProxy -> chainProxySummaryTemplate.formatTemplate("count" to proxyServer.proxyServerIds.size)
            else -> proxyServer.getInfo().address
        }
    }

    private fun StrategyGroup.strategyGroupSummary(): String {
        val template = if (filter.isBlank()) {
            strategyGroupSummaryTemplate
        } else {
            strategyGroupSummaryWithFilterTemplate
        }
        return template.formatTemplate(
            "strategy" to strategyDisplayName(),
            "group" to sourceGroupName(),
            "filter" to filter,
        )
    }

    private fun StrategyGroup.strategyDisplayName(): String {
        return when (strategy) {
            StrategyGroupConstants.TYPE_LEAST_PING -> leastPingName
            StrategyGroupConstants.TYPE_LEAST_LOAD -> leastLoadName
            StrategyGroupConstants.TYPE_RANDOM -> randomName
            StrategyGroupConstants.TYPE_ROUND_ROBIN -> roundRobinName
            else -> strategy
        }
    }

    private fun StrategyGroup.sourceGroupName(): String {
        return subscriptionGroupId?.let { groupId -> groupNames[groupId] ?: unknownGroupName } ?: allGroupsName
    }
}

@Composable
internal fun rememberProxyServerListItemTextFormatter(
    groupNames: Map<Int, String>,
    unknownGroupName: String,
): ProxyServerListItemTextFormatter {
    val allGroupsName = stringResource(R.string.proxy_editor_strategy_group_all_groups)
    val leastPingName = stringResource(R.string.proxy_editor_strategy_group_least_ping)
    val leastLoadName = stringResource(R.string.proxy_editor_strategy_group_least_load)
    val randomName = stringResource(R.string.proxy_editor_strategy_group_random)
    val roundRobinName = stringResource(R.string.proxy_editor_strategy_group_round_robin)
    val strategyGroupSummaryTemplate = stringResource(R.string.proxy_server_list_strategy_group_summary)
    val strategyGroupSummaryWithFilterTemplate =
        stringResource(R.string.proxy_server_list_strategy_group_summary_with_filter)
    val chainProxySummaryTemplate = stringResource(R.string.proxy_server_list_chain_proxy_summary)

    return remember(
        groupNames,
        unknownGroupName,
        allGroupsName,
        leastPingName,
        leastLoadName,
        randomName,
        roundRobinName,
        strategyGroupSummaryTemplate,
        strategyGroupSummaryWithFilterTemplate,
        chainProxySummaryTemplate,
    ) {
        ProxyServerListItemTextFormatter(
            groupNames = groupNames,
            unknownGroupName = unknownGroupName,
            allGroupsName = allGroupsName,
            leastPingName = leastPingName,
            leastLoadName = leastLoadName,
            randomName = randomName,
            roundRobinName = roundRobinName,
            strategyGroupSummaryTemplate = strategyGroupSummaryTemplate,
            strategyGroupSummaryWithFilterTemplate = strategyGroupSummaryWithFilterTemplate,
            chainProxySummaryTemplate = chainProxySummaryTemplate,
        )
    }
}
