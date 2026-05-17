@file:OptIn(ExperimentalScrollBarApi::class)

package features.proxy.server.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.res.stringResource
import app.AppState
import app.ProxyServerState
import app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import features.proxy.server.model.getUrlOrNull
import app.navigation.Navigator
import app.navigation.Route
import data.AndroidAppStateStore
import ui.feedback.AndroidToastTipNotifier
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import ui.text.formatTemplate
import ui.layout.pageScrollModifiers
import ui.clipboard.setPlainText

@Composable
internal fun ProxyServerListPager(
    groupPagerState: PagerState,
    groupState: ProxyServerListGroups,
    searchValue: String,
    servers: List<ProxyServerState>,
    selectedServerId: Int,
    unknownGroupName: String,
    itemTextFormatter: ProxyServerListItemTextFormatter,
    topAppBarScrollBehavior: ScrollBehavior,
    listPadding: PaddingValues,
    contentPadding: PaddingValues,
    stateStore: AndroidAppStateStore,
    updateAppState: ((AppState) -> AppState) -> Unit,
    navigator: Navigator,
    clipboard: Clipboard,
    tipNotifier: AndroidToastTipNotifier,
    scope: CoroutineScope,
    messages: ProxyServerListMessages,
    resultKey: String,
    onSelectedServerIdChange: (Int) -> Unit,
    onRestartProxyServiceSilently: (Int) -> Unit,
) {
    HorizontalPager(
        state = groupPagerState,
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.Top,
    ) {
        val pageGroupId = groupState.groupTabs.getOrNull(it)?.id ?: groupState.selectedTabId
        val pageIsAllGroupsSelected = pageGroupId == AllProxyGroupId
        val keyword = searchValue.trim()
        val pageServers = servers.filter { server ->
            val info = server.server.getInfo()
            ((pageIsAllGroupsSelected && server.groupId in groupState.visibleGroupIds) || server.groupId == pageGroupId) &&
                (
                    keyword.isEmpty() ||
                        info.remarks.contains(keyword, ignoreCase = true)
                    )
        }
        val lazyListState = rememberLazyListState()

        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.pageScrollModifiers(topAppBarScrollBehavior),
                contentPadding = listPadding,
            ) {
                if (pageServers.isEmpty()) {
                    item(key = "proxy_empty", contentType = "empty") {
                        ProxyServerListEmptyState(text = stringResource(R.string.common_empty))
                    }
                } else {
                    items(
                        items = pageServers,
                        key = { server -> server.id },
                        contentType = { "proxy_server" },
                    ) { server ->
                        ProxyServerListItem(
                            server = server,
                            selectedServerId = selectedServerId,
                            pageIsAllGroupsSelected = pageIsAllGroupsSelected,
                            pageGroupId = pageGroupId,
                            unknownGroupName = unknownGroupName,
                            itemTextFormatter = itemTextFormatter,
                            groupState = groupState,
                            stateStore = stateStore,
                            updateAppState = updateAppState,
                            navigator = navigator,
                            clipboard = clipboard,
                            tipNotifier = tipNotifier,
                            scope = scope,
                            messages = messages,
                            resultKey = resultKey,
                            onSelectedServerIdChange = onSelectedServerIdChange,
                            onRestartProxyServiceSilently = onRestartProxyServiceSilently,
                        )
                    }
                }
            }
            VerticalScrollBar(
                adapter = rememberScrollBarAdapter(lazyListState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                trackPadding = contentPadding,
            )
        }
    }
}

@Composable
private fun ProxyServerListItem(
    server: ProxyServerState,
    selectedServerId: Int,
    pageIsAllGroupsSelected: Boolean,
    pageGroupId: Int,
    unknownGroupName: String,
    itemTextFormatter: ProxyServerListItemTextFormatter,
    groupState: ProxyServerListGroups,
    stateStore: AndroidAppStateStore,
    updateAppState: ((AppState) -> AppState) -> Unit,
    navigator: Navigator,
    clipboard: Clipboard,
    tipNotifier: AndroidToastTipNotifier,
    scope: CoroutineScope,
    messages: ProxyServerListMessages,
    resultKey: String,
    onSelectedServerIdChange: (Int) -> Unit,
    onRestartProxyServiceSilently: (Int) -> Unit,
) {
    ProxyServerListItemCard(
        server = server,
        displayText = itemTextFormatter.displayOf(server),
        selected = selectedServerId == server.id,
        groupName = if (pageIsAllGroupsSelected) {
            groupState.groupNames[server.groupId] ?: unknownGroupName
        } else {
            null
        },
        onSelect = {
            val shouldRestart = stateStore.state.value.proxyRunning &&
                stateStore.state.value.selectedProxyServerId != server.id
            onSelectedServerIdChange(server.id)
            updateAppState { state ->
                if (state.selectedProxyServerId == server.id) {
                    state
                } else {
                    state.copy(selectedProxyServerId = server.id)
                }
            }
            if (shouldRestart) {
                onRestartProxyServiceSilently(server.id)
            }
        },
        onShare = {
            scope.launch {
                val url = runCatching { server.server.getUrlOrNull() }.getOrNull()
                if (url == null) {
                    tipNotifier.show(messages.unsupported)
                } else {
                    clipboard.setPlainText(url)
                    tipNotifier.show(messages.copied)
                }
            }
        },
        onEdit = {
            navigator.navigateForResult(
                route = Route.ProxyServerEditor(
                    ps = server.server,
                    serverId = server.id,
                    groupId = server.groupId,
                    returnGroupId = pageGroupId,
                    resultKey = resultKey,
                ),
                requestKey = resultKey,
            )
        },
        onDelete = {
            var deleted = false
            updateAppState { state ->
                if (state.selectedProxyServerId == server.id) {
                    state
                } else {
                    deleted = true
                    state.copy(
                        proxyServers = state.proxyServers.filterNot { it.id == server.id },
                    )
                }
            }
            scope.launch {
                tipNotifier.show(
                    if (deleted) {
                        messages.deletedTemplate.formatTemplate("name" to server.server.getInfo().remarks)
                    } else {
                        messages.deleteSelectedBlocked
                    },
                )
            }
        },
    )
}
