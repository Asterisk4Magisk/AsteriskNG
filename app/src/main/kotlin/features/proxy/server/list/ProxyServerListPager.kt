// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(ExperimentalFoundationApi::class, ExperimentalScrollBarApi::class)

package features.proxy.server.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import app.AppState
import app.ProxyServerState
import app.R
import app.collectProxyServerLatency
import app.navigation.Navigator
import app.navigation.Route
import data.AndroidAppStateStore
import features.proxy.server.model.UrlProxyServer
import features.proxy.server.validation.rememberProxyServerValidationMessageResolver
import features.proxy.server.usecase.ProxyServerCopyTextResult
import features.proxy.server.usecase.ProxyServerCopyTextType
import features.proxy.server.usecase.proxyServerCopyText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import ui.clipboard.setPlainText
import ui.components.longPressReorderDragHandle
import ui.components.moveItem
import ui.components.rememberAsteriskReorderableLazyListState
import ui.components.rememberReorderableLazyListContentPaddingWithoutTop
import ui.components.rememberReorderableScrollThresholdPadding
import ui.feedback.AndroidToastTipNotifier
import ui.layout.pageScrollModifiers

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
    dragScrollThresholdBottomPadding: Dp,
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
    onDeleteServer: (ProxyServerState) -> Unit,
) {
    val context = LocalContext.current
    var qrCodeDialogState by remember { mutableStateOf<ProxyServerQrCodeDialogState?>(null) }

    qrCodeDialogState?.let { state ->
        ProxyServerQrCodeDialog(
            title = state.title,
            text = state.text,
            onDismissRequest = { qrCodeDialogState = null },
        )
    }

    HorizontalPager(
        state = groupPagerState,
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.Top,
    ) {
        val pageGroupId = groupState.groupTabs.getOrNull(it)?.id ?: groupState.selectedTabId
        val pageIsAllGroupsSelected = pageGroupId == AllProxyGroupId
        val keyword = searchValue.trim()
        val pageServers = servers.filterPageServers(
            pageGroupId = pageGroupId,
            pageIsAllGroupsSelected = pageIsAllGroupsSelected,
            visibleGroupIds = groupState.visibleGroupIds,
            keyword = keyword,
        )
        val lazyListState = rememberLazyListState()
        val lazyContentPadding = rememberReorderableLazyListContentPaddingWithoutTop(listPadding)
        val reorderableLazyListState = rememberAsteriskReorderableLazyListState(
            lazyListState = lazyListState,
            itemCount = pageServers.size,
            scrollThresholdPadding = rememberReorderableScrollThresholdPadding(
                bottom = dragScrollThresholdBottomPadding,
            ),
        ) { fromIndex, toIndex ->
            updateAppState { state ->
                val currentPageServers = state.proxyServers.filterPageServers(
                    pageGroupId = pageGroupId,
                    pageIsAllGroupsSelected = pageIsAllGroupsSelected,
                    visibleGroupIds = groupState.visibleGroupIds,
                    keyword = keyword,
                )
                val reorderedServers = state.proxyServers.reorderVisibleServer(
                    pageServers = currentPageServers,
                    fromIndex = fromIndex,
                    toIndex = toIndex,
                )
                if (reorderedServers === state.proxyServers) {
                    state
                } else {
                    state.copy(proxyServers = reorderedServers)
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .padding(top = listPadding.calculateTopPadding())
                    .pageScrollModifiers(topAppBarScrollBehavior),
                contentPadding = lazyContentPadding,
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
                        ReorderableItem(reorderableLazyListState.reorderableState, key = server.id) { isDragging ->
                            ProxyServerListItem(
                                server = server,
                                servers = servers,
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
                                context = context,
                                tipNotifier = tipNotifier,
                                scope = scope,
                                messages = messages,
                                resultKey = resultKey,
                                onSelectedServerIdChange = onSelectedServerIdChange,
                                onDeleteServer = onDeleteServer,
                                onShowQrCode = { title, text ->
                                    qrCodeDialogState = ProxyServerQrCodeDialogState(title, text)
                                },
                                isDragging = isDragging,
                                dragModifier = Modifier.longPressReorderDragHandle(
                                    scope = this,
                                    enabled = pageServers.size > 1,
                                    state = reorderableLazyListState,
                                ),
                                modifier = Modifier.animateItem(
                                    fadeInSpec = null,
                                    fadeOutSpec = null,
                                    placementSpec = folmeSpring(damping = 0.9f, response = 0.38f),
                                ),
                            )
                        }
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
    servers: List<ProxyServerState>,
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
    context: android.content.Context,
    tipNotifier: AndroidToastTipNotifier,
    scope: CoroutineScope,
    messages: ProxyServerListMessages,
    resultKey: String,
    onSelectedServerIdChange: (Int) -> Unit,
    onDeleteServer: (ProxyServerState) -> Unit,
    onShowQrCode: (title: String, text: String) -> Unit,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
    dragModifier: Modifier,
) {
    val latency = stateStore.collectProxyServerLatency(
        serverId = server.id,
        initialLatency = server.latency,
    ).value
    val validationMessageOf = rememberProxyServerValidationMessageResolver()
    val displayText = itemTextFormatter.displayOf(server, servers)
    val copyActions = if (server.server is UrlProxyServer<*>) {
        listOf(
            ProxyServerListCopyAction.QrCode,
            ProxyServerListCopyAction.Url,
            ProxyServerListCopyAction.FullJson,
        )
    } else {
        listOf(ProxyServerListCopyAction.FullJson)
    }

    ProxyServerListItemCard(
        latency = latency,
        displayText = displayText,
        selected = selectedServerId == server.id,
        modifier = modifier,
        groupName = if (pageIsAllGroupsSelected) {
            groupState.groupNames[server.groupId] ?: unknownGroupName
        } else {
            null
        },
        isDragging = isDragging,
        dragModifier = dragModifier,
        onSelect = {
            onSelectedServerIdChange(server.id)
            updateAppState { state ->
                if (state.selectedProxyServerId == server.id) {
                    state
                } else {
                    state.copy(selectedProxyServerId = server.id)
                }
            }
        },
        copyActions = copyActions,
        onCopyAction = { action ->
            scope.launch {
                val basicIssues = server.server.validateBasic()
                if (basicIssues.isNotEmpty()) {
                    tipNotifier.show(validationMessageOf(basicIssues.first()))
                    return@launch
                }
                val copyTextType = when (action) {
                    ProxyServerListCopyAction.QrCode,
                    ProxyServerListCopyAction.Url -> ProxyServerCopyTextType.Url

                    ProxyServerListCopyAction.FullJson -> ProxyServerCopyTextType.FullJson
                }
                when (
                    val result = server.proxyServerCopyText(
                        context = context,
                        appState = stateStore.state.value,
                        type = copyTextType,
                    )
                ) {
                    is ProxyServerCopyTextResult.Success -> {
                        if (action == ProxyServerListCopyAction.QrCode) {
                            onShowQrCode(displayText.title, result.text)
                        } else {
                            clipboard.setPlainText(result.text)
                            tipNotifier.show(messages.copied)
                        }
                    }

                    ProxyServerCopyTextResult.Unsupported -> {
                        tipNotifier.show(messages.unsupported)
                    }

                    ProxyServerCopyTextResult.InvalidConfig -> {
                        tipNotifier.show(messages.configInvalid)
                    }
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
            onDeleteServer(server)
        },
    )
}

private data class ProxyServerQrCodeDialogState(
    val title: String,
    val text: String,
)

private fun List<ProxyServerState>.filterPageServers(
    pageGroupId: Int,
    pageIsAllGroupsSelected: Boolean,
    visibleGroupIds: Set<Int>,
    keyword: String,
): List<ProxyServerState> {
    return filter { server ->
        val groupMatches = if (pageIsAllGroupsSelected) {
            server.groupId in visibleGroupIds
        } else {
            server.groupId == pageGroupId
        }
        groupMatches && (
            keyword.isEmpty() ||
                server.server.getInfo().remarks.contains(keyword, ignoreCase = true)
            )
    }
}

private fun List<ProxyServerState>.reorderVisibleServer(
    pageServers: List<ProxyServerState>,
    fromIndex: Int,
    toIndex: Int,
): List<ProxyServerState> {
    val serverId = pageServers.getOrNull(fromIndex)?.id ?: return this
    val targetServerId = pageServers.getOrNull(toIndex)?.id ?: return this

    return moveItem(
        fromIndex = indexOfFirst { server -> server.id == serverId },
        toIndex = indexOfFirst { server -> server.id == targetServerId },
    )
}
