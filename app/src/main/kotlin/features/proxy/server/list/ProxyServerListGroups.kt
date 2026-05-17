package features.proxy.server.list

import app.ProxyServerListState
import app.ProxyServerState
import app.SubscriptionGroupState
import features.proxy.server.display.displayName
import features.proxy.server.display.displayNameById

internal const val AllProxyGroupId = 0

internal data class ProxyServerListGroups(
    val visibleGroups: List<SubscriptionGroupState>,
    val showGroupTabs: Boolean,
    val selectedGroup: SubscriptionGroupState,
    val selectedTabId: Int,
    val isAllGroupsSelected: Boolean,
    val groupNames: Map<Int, String>,
    val visibleGroupIds: Set<Int>,
    val visibleServers: List<ProxyServerState>,
    val groupTabs: List<ProxyServerListGroupTabUi>,
    val selectedTabIndex: Int,
    val currentGroupServers: List<ProxyServerState>,
    val currentFilteredServers: List<ProxyServerState>,
)

internal fun proxyServerListGroups(
    state: ProxyServerListState,
    selectedGroupId: Int,
    searchValue: String,
    allGroupName: String,
    defaultGroupName: String,
): ProxyServerListGroups {
    val servers = state.proxyServers
    val visibleGroups = state.subscriptionGroups.filter { it.enabled || it.builtIn }
        .ifEmpty { state.subscriptionGroups.take(1) }
    val showGroupTabs = visibleGroups.size > 1
    val showAllProxyGroup = state.enableAllProxyGroup && showGroupTabs
    val selectedGroup = visibleGroups.firstOrNull { it.id == selectedGroupId } ?: visibleGroups.first()
    val selectedTabId = if (showAllProxyGroup && selectedGroupId == AllProxyGroupId) {
        AllProxyGroupId
    } else {
        selectedGroup.id
    }
    val isAllGroupsSelected = selectedTabId == AllProxyGroupId
    val groupNames = state.subscriptionGroups.displayNameById(defaultGroupName)
    val visibleGroupIds = visibleGroups.map { it.id }.toSet()
    val visibleServers = servers.filter { it.groupId in visibleGroupIds }
    val groupTabs = proxyServerListGroupTabs(
        visibleGroups = visibleGroups,
        showAllProxyGroup = showAllProxyGroup,
        allGroupName = allGroupName,
        defaultGroupName = defaultGroupName,
        servers = servers,
        visibleServers = visibleServers,
    )
    val selectedTabIndex = groupTabs.indexOfFirst { group -> group.id == selectedTabId }
        .coerceAtLeast(0)
    val currentGroupServers = if (isAllGroupsSelected) {
        visibleServers
    } else {
        servers.filter { server -> server.groupId == selectedTabId }
    }
    val currentFilterKeyword = searchValue.trim()
    val currentFilteredServers = currentGroupServers.filter { server ->
        val info = server.server.getInfo()
        currentFilterKeyword.isEmpty() || info.remarks.contains(currentFilterKeyword, ignoreCase = true)
    }

    return ProxyServerListGroups(
        visibleGroups = visibleGroups,
        showGroupTabs = showGroupTabs,
        selectedGroup = selectedGroup,
        selectedTabId = selectedTabId,
        isAllGroupsSelected = isAllGroupsSelected,
        groupNames = groupNames,
        visibleGroupIds = visibleGroupIds,
        visibleServers = visibleServers,
        groupTabs = groupTabs,
        selectedTabIndex = selectedTabIndex,
        currentGroupServers = currentGroupServers,
        currentFilteredServers = currentFilteredServers,
    )
}

private fun proxyServerListGroupTabs(
    visibleGroups: List<SubscriptionGroupState>,
    showAllProxyGroup: Boolean,
    allGroupName: String,
    defaultGroupName: String,
    servers: List<ProxyServerState>,
    visibleServers: List<ProxyServerState>,
): List<ProxyServerListGroupTabUi> {
    val groupTabs = visibleGroups.map { group ->
        ProxyServerListGroupTabUi(
            id = group.id,
            name = group.displayName(defaultGroupName),
            serverCount = servers.count { server -> server.groupId == group.id },
        )
    }
    return if (showAllProxyGroup) {
        listOf(
            ProxyServerListGroupTabUi(
                id = AllProxyGroupId,
                name = allGroupName,
                serverCount = visibleServers.size,
            ),
        ) + groupTabs
    } else {
        groupTabs
    }
}
