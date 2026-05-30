// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.list

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.unit.dp
import app.AppState
import app.ProxyServerListState
import app.ProxyServerState
import app.R
import app.navigation.Navigator
import app.navigation.Route
import data.AndroidAppStateStore
import engine.proxy.latency.ProxyServerLatencyTestMode
import features.proxy.server.model.getUrlOrNull
import features.proxy.server.usecase.ProxyServiceResult
import features.proxy.server.usecase.ProxyServiceUseCase
import features.proxy.server.usecase.ProxyServerImportFileUseCase
import features.proxy.server.usecase.ProxyServerImportSource
import features.proxy.server.usecase.createProxyServer
import features.proxy.server.usecase.deleteDuplicateServersInGroup
import features.proxy.server.usecase.importProxyServersFromText
import features.proxy.server.usecase.sortedInGroupByLatencyResult
import features.proxy.server.usecase.updatableSubscriptionGroups
import features.proxy.server.usecase.withImportedProxyServers
import features.proxy.server.usecase.withUpdatedSubscriptionServers
import features.subscription.DefaultSubscriptionGroupId
import features.subscription.SubscriptionInstallConfigUseCase
import features.subscription.runtime.AndroidSubscriptionFetcher
import features.subscription.usecase.subscriptionUpdateMessage
import features.subscription.usecase.toSubscriptionFetchOptions
import features.subscription.usecase.updateSubscriptions
import features.subscription.toSubscriptionInstallConfigOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import ui.clipboard.getPlainText
import ui.clipboard.setPlainText
import ui.feedback.AndroidToastTipNotifier
import ui.layout.AdaptiveTopAppBar
import ui.text.formatTemplate

@Composable
internal fun ProxyServerListTopBar(
    isWideScreen: Boolean,
    scrollBehavior: ScrollBehavior,
    searchValue: String,
    onSearchValueChange: (String) -> Unit,
    groupState: ProxyServerListGroups,
    selectedServer: ProxyServerState?,
    proxyListState: ProxyServerListState,
    stateStore: AndroidAppStateStore,
    updateAppState: ((AppState) -> AppState) -> Unit,
    navigator: Navigator,
    qrScanner: suspend () -> String?,
    proxyServerImportFileUseCase: ProxyServerImportFileUseCase,
    subscriptionFetcher: AndroidSubscriptionFetcher,
    proxyServiceUseCase: ProxyServiceUseCase,
    clipboard: Clipboard,
    tipNotifier: AndroidToastTipNotifier,
    scope: CoroutineScope,
    messages: ProxyServerListMessages,
    resultKey: String,
    serviceOperationInProgress: Boolean,
    runProxyServiceOperation: (suspend () -> Unit) -> Unit,
    onSelectedGroupIdChange: (Int) -> Unit,
    onTestProxyServerLatency: (List<ProxyServerState>, ProxyServerLatencyTestMode, String, Boolean) -> Unit,
) {
    AdaptiveTopAppBar(
        title = androidx.compose.ui.res.stringResource(R.string.proxy_server_list_title),
        isWideScreen = isWideScreen,
        scrollBehavior = scrollBehavior,
        actions = {
            ProxyServerListAddMenu { action ->
                handleProxyServerListAddAction(
                    action = action,
                    groupState = groupState,
                    proxyListState = proxyListState,
                    stateStore = stateStore,
                    updateAppState = updateAppState,
                    navigator = navigator,
                    qrScanner = qrScanner,
                    proxyServerImportFileUseCase = proxyServerImportFileUseCase,
                    subscriptionFetcher = subscriptionFetcher,
                    clipboard = clipboard,
                    tipNotifier = tipNotifier,
                    scope = scope,
                    messages = messages,
                    resultKey = resultKey,
                )
            }
            ProxyServerListToolsMenu { action ->
                handleProxyServerListToolAction(
                    action = action,
                    groupState = groupState,
                    selectedServer = selectedServer,
                    proxyListState = proxyListState,
                    stateStore = stateStore,
                    updateAppState = updateAppState,
                    subscriptionFetcher = subscriptionFetcher,
                    proxyServiceUseCase = proxyServiceUseCase,
                    clipboard = clipboard,
                    tipNotifier = tipNotifier,
                    scope = scope,
                    messages = messages,
                    serviceOperationInProgress = serviceOperationInProgress,
                    runProxyServiceOperation = runProxyServiceOperation,
                    onTestProxyServerLatency = onTestProxyServerLatency,
                )
            }
        },
        bottomContent = {
            Column {
                ProxyServerListSearchBar(
                    searchValue = searchValue,
                    onSearchValueChange = onSearchValueChange,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                )
                if (groupState.showGroupTabs) {
                    ProxyServerListGroupTabs(
                        groups = groupState.groupTabs,
                        selectedGroupId = groupState.selectedTabId,
                        onGroupSelected = onSelectedGroupIdChange,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
        },
    )
}

private fun handleProxyServerListAddAction(
    action: ProxyServerListAddAction,
    groupState: ProxyServerListGroups,
    proxyListState: ProxyServerListState,
    stateStore: AndroidAppStateStore,
    updateAppState: ((AppState) -> AppState) -> Unit,
    navigator: Navigator,
    qrScanner: suspend () -> String?,
    proxyServerImportFileUseCase: ProxyServerImportFileUseCase,
    subscriptionFetcher: AndroidSubscriptionFetcher,
    clipboard: Clipboard,
    tipNotifier: AndroidToastTipNotifier,
    scope: CoroutineScope,
    messages: ProxyServerListMessages,
    resultKey: String,
) {
    when (action) {
        ProxyServerListAddAction.ScanQrCode -> {
            scope.launch {
                runCatching { qrScanner() }
                    .onSuccess { scanText ->
                        if (scanText.isNullOrBlank()) return@onSuccess
                        if (
                            installSubscriptionFromQrCode(
                                text = scanText,
                                stateStore = stateStore,
                                subscriptionFetcher = subscriptionFetcher,
                                tipNotifier = tipNotifier,
                                messages = messages,
                            )
                        ) {
                            return@onSuccess
                        }
                        importProxyServers(
                            text = scanText,
                            source = ProxyServerImportSource.QrCode,
                            groupState = groupState,
                            updateAppState = updateAppState,
                            tipNotifier = tipNotifier,
                            messages = messages,
                        )
                    }
                    .onFailure { error -> tipNotifier.showError(error) }
            }
        }

        ProxyServerListAddAction.Clipboard -> {
            scope.launch {
                importProxyServers(
                    text = clipboard.getPlainText().orEmpty(),
                    source = ProxyServerImportSource.Clipboard,
                    groupState = groupState,
                    updateAppState = updateAppState,
                    tipNotifier = tipNotifier,
                    messages = messages,
                )
            }
        }

        ProxyServerListAddAction.File -> {
            scope.launch {
                runCatching {
                    proxyServerImportFileUseCase.readText()?.let { text ->
                        importProxyServers(
                            text = text,
                            source = ProxyServerImportSource.File,
                            groupState = groupState,
                            updateAppState = updateAppState,
                            tipNotifier = tipNotifier,
                            messages = messages,
                        )
                    }
                }.onFailure { error -> tipNotifier.showError(error) }
            }
        }

        else -> {
            val serverId = proxyListState.nextProxyServerId
            navigator.navigateForResult(
                route = Route.ProxyServerEditor(
                    ps = createProxyServer(action),
                    serverId = serverId,
                    groupId = if (groupState.isAllGroupsSelected) {
                        DefaultSubscriptionGroupId
                    } else {
                        groupState.selectedGroup.id
                    },
                    returnGroupId = groupState.selectedTabId,
                    resultKey = resultKey,
                ),
                requestKey = resultKey,
            )
        }
    }
}

private suspend fun installSubscriptionFromQrCode(
    text: String,
    stateStore: AndroidAppStateStore,
    subscriptionFetcher: AndroidSubscriptionFetcher,
    tipNotifier: AndroidToastTipNotifier,
    messages: ProxyServerListMessages,
): Boolean {
    val config = text.toSubscriptionInstallConfigOrNull() ?: return false
    runCatching {
        SubscriptionInstallConfigUseCase(
            stateStore = stateStore,
            subscriptionFetcher = subscriptionFetcher,
        ).install(config)
    }.onSuccess { result ->
        tipNotifier.show(
            subscriptionUpdateMessage(
                result = result,
                successTemplate = messages.subscriptionUpdateResultTemplate,
                failedTemplate = messages.subscriptionUpdateResultWithFailedTemplate,
            ),
        )
    }.onFailure { error ->
        tipNotifier.showError(error)
    }
    return true
}

private suspend fun importProxyServers(
    text: String,
    source: ProxyServerImportSource,
    groupState: ProxyServerListGroups,
    updateAppState: ((AppState) -> AppState) -> Unit,
    tipNotifier: AndroidToastTipNotifier,
    messages: ProxyServerListMessages,
) {
    val targetGroupId = if (groupState.isAllGroupsSelected) {
        DefaultSubscriptionGroupId
    } else {
        groupState.selectedGroup.id
    }
    val importResult = importProxyServersFromText(
        text = text,
        source = source,
    )
    if (importResult.servers.isNotEmpty()) {
        updateAppState { state -> state.withImportedProxyServers(importResult, targetGroupId) }
    }
    tipNotifier.show(
        messages.importResultTemplate.formatTemplate(
            "serverCount" to importResult.servers.size,
        ),
    )
}

private fun handleProxyServerListToolAction(
    action: ProxyServerListToolAction,
    groupState: ProxyServerListGroups,
    selectedServer: ProxyServerState?,
    proxyListState: ProxyServerListState,
    stateStore: AndroidAppStateStore,
    updateAppState: ((AppState) -> AppState) -> Unit,
    subscriptionFetcher: AndroidSubscriptionFetcher,
    proxyServiceUseCase: ProxyServiceUseCase,
    clipboard: Clipboard,
    tipNotifier: AndroidToastTipNotifier,
    scope: CoroutineScope,
    messages: ProxyServerListMessages,
    serviceOperationInProgress: Boolean,
    runProxyServiceOperation: (suspend () -> Unit) -> Unit,
    onTestProxyServerLatency: (List<ProxyServerState>, ProxyServerLatencyTestMode, String, Boolean) -> Unit,
) {
    when (action) {
        ProxyServerListToolAction.RestartService -> {
            restartSelectedProxyService(
                selectedServer = selectedServer,
                stateStore = stateStore,
                updateAppState = updateAppState,
                proxyServiceUseCase = proxyServiceUseCase,
                tipNotifier = tipNotifier,
                messages = messages,
                serviceOperationInProgress = serviceOperationInProgress,
                runProxyServiceOperation = runProxyServiceOperation,
            )
        }

        ProxyServerListToolAction.TestLatency -> {
            onTestProxyServerLatency(
                groupState.currentFilteredServers,
                ProxyServerLatencyTestMode.TcpConnect,
                messages.latencyDoneTemplate,
                false,
            )
        }

        ProxyServerListToolAction.TestRealConnection -> {
            onTestProxyServerLatency(
                groupState.currentFilteredServers,
                ProxyServerLatencyTestMode.RealConnection,
                messages.realConnectionDoneTemplate,
                false,
            )
        }

        ProxyServerListToolAction.SortByTestResult -> {
            sortCurrentGroupByTestResult(
                servers = groupState.currentGroupServers,
                updateAppState = updateAppState,
                tipNotifier = tipNotifier,
                scope = scope,
                messages = messages,
            )
        }

        ProxyServerListToolAction.UpdateSubscriptions -> {
            updateSubscriptionGroups(
                proxyListState = proxyListState,
                stateStore = stateStore,
                updateAppState = updateAppState,
                subscriptionFetcher = subscriptionFetcher,
                tipNotifier = tipNotifier,
                scope = scope,
                messages = messages,
            )
        }

        ProxyServerListToolAction.CopyAllUrls -> {
            copyCurrentGroupUrls(
                servers = groupState.currentGroupServers,
                clipboard = clipboard,
                tipNotifier = tipNotifier,
                scope = scope,
                messages = messages,
            )
        }

        ProxyServerListToolAction.DeleteDuplicateServers -> {
            deleteDuplicateServers(
                servers = groupState.currentGroupServers,
                updateAppState = updateAppState,
                tipNotifier = tipNotifier,
                scope = scope,
                messages = messages,
            )
        }
    }
}

private fun sortCurrentGroupByTestResult(
    servers: List<ProxyServerState>,
    updateAppState: ((AppState) -> AppState) -> Unit,
    tipNotifier: AndroidToastTipNotifier,
    scope: CoroutineScope,
    messages: ProxyServerListMessages,
) {
    val currentGroupServerIds = servers.map { server -> server.id }.toSet()
    updateAppState { state ->
        val sortedServers = state.proxyServers.sortedInGroupByLatencyResult(currentGroupServerIds)
        if (sortedServers === state.proxyServers) {
            state
        } else {
            state.copy(proxyServers = sortedServers)
        }
    }
    scope.launch {
        tipNotifier.show(messages.sortDone)
    }
}

private fun restartSelectedProxyService(
    selectedServer: ProxyServerState?,
    stateStore: AndroidAppStateStore,
    updateAppState: ((AppState) -> AppState) -> Unit,
    proxyServiceUseCase: ProxyServiceUseCase,
    tipNotifier: AndroidToastTipNotifier,
    messages: ProxyServerListMessages,
    serviceOperationInProgress: Boolean,
    runProxyServiceOperation: (suspend () -> Unit) -> Unit,
) {
    if (serviceOperationInProgress) return
    runProxyServiceOperation {
        when (
            val result = proxyServiceUseCase.restart(
                state = stateStore.state.value,
                selectedServer = selectedServer,
            )
        ) {
            is ProxyServiceResult.Success -> {
                updateAppState { state -> state.copy(proxyRunning = result.proxyRunning) }
                tipNotifier.show(messages.serviceRestarted)
            }

            ProxyServiceResult.MissingServer -> {
                tipNotifier.show(messages.selectServerFirst)
            }

            is ProxyServiceResult.Failed -> {
                updateAppState { state -> state.copy(proxyRunning = false) }
                tipNotifier.showError(result.error, messages.serviceStopped)
            }
        }
    }
}

private fun updateSubscriptionGroups(
    proxyListState: ProxyServerListState,
    stateStore: AndroidAppStateStore,
    updateAppState: ((AppState) -> AppState) -> Unit,
    subscriptionFetcher: AndroidSubscriptionFetcher,
    tipNotifier: AndroidToastTipNotifier,
    scope: CoroutineScope,
    messages: ProxyServerListMessages,
) {
    val subscriptionGroups = proxyListState.subscriptionGroups.updatableSubscriptionGroups()
    scope.launch {
        if (subscriptionGroups.isEmpty()) {
            tipNotifier.show(messages.noSubscriptionUpdates)
            return@launch
        }
        val result = updateSubscriptions(
            groups = subscriptionGroups,
            subscriptionFetcher = subscriptionFetcher,
            fetchOptions = { group -> stateStore.state.value.toSubscriptionFetchOptions(group) },
        )
        if (result.updates.isNotEmpty()) {
            updateAppState { state ->
                state.withUpdatedSubscriptionServers(
                    updates = result.updates,
                    updatedAtMillis = result.updatedAtMillis,
                )
            }
        }
        tipNotifier.show(
            subscriptionUpdateMessage(
                result = result,
                successTemplate = messages.subscriptionUpdateResultTemplate,
                failedTemplate = messages.subscriptionUpdateResultWithFailedTemplate,
            ),
        )
    }
}

private fun copyCurrentGroupUrls(
    servers: List<ProxyServerState>,
    clipboard: Clipboard,
    tipNotifier: AndroidToastTipNotifier,
    scope: CoroutineScope,
    messages: ProxyServerListMessages,
) {
    scope.launch {
        val urls = servers
            .mapNotNull { server -> runCatching { server.server.getUrlOrNull() }.getOrNull() }
            .joinToString("\n")
        if (urls.isBlank()) {
            tipNotifier.show(messages.unsupported)
        } else {
            clipboard.setPlainText(urls)
            tipNotifier.show(messages.copied)
        }
    }
}

private fun deleteDuplicateServers(
    servers: List<ProxyServerState>,
    updateAppState: ((AppState) -> AppState) -> Unit,
    tipNotifier: AndroidToastTipNotifier,
    scope: CoroutineScope,
    messages: ProxyServerListMessages,
) {
    val currentGroupServerIds = servers.map { server -> server.id }.toSet()
    var removedCount = 0
    updateAppState { state ->
        val result = state.proxyServers.deleteDuplicateServersInGroup(
            currentGroupServerIds = currentGroupServerIds,
            selectedProxyServerId = state.selectedProxyServerId,
        )
        removedCount = result.removedCount
        if (removedCount == 0) {
            state
        } else {
            state.copy(proxyServers = result.servers)
        }
    }
    scope.launch {
        tipNotifier.show(
            if (removedCount > 0) {
                messages.duplicatesDeletedTemplate.formatTemplate("count" to removedCount)
            } else {
                messages.noDuplicates
            },
        )
    }
}
