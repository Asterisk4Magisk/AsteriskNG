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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import features.subscription.DefaultSubscriptionGroupId
import features.proxy.server.model.getUrlOrNull
import app.navigation.Navigator
import app.navigation.Route
import engine.proxy.latency.ProxyServerLatencyTestMode
import features.proxy.server.qr.ProxyServerQrScanUseCase
import features.proxy.server.usecase.ProxyServiceResult
import features.proxy.server.usecase.ProxyServiceUseCase
import features.subscription.SubscriptionFetchUseCase
import data.AndroidAppStateStore
import features.proxy.server.usecase.createProxyServer
import features.proxy.server.usecase.deleteDuplicateServersInGroup
import features.proxy.server.usecase.importProxyServersFromText
import features.proxy.server.usecase.updatableSubscriptionGroups
import features.proxy.server.usecase.withImportedProxyServers
import features.proxy.server.usecase.withUpdatedSubscriptionServers
import ui.feedback.AndroidToastTipNotifier
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import ui.layout.AdaptiveTopAppBar
import ui.text.formatTemplate
import ui.clipboard.getPlainText
import ui.clipboard.setPlainText
import features.subscription.usecase.subscriptionUpdateMessage
import features.subscription.usecase.toSubscriptionFetchOptions
import features.subscription.usecase.updateSubscriptions

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
    qrScanner: ProxyServerQrScanUseCase,
    subscriptionFetchUseCase: SubscriptionFetchUseCase,
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
                    updateAppState = updateAppState,
                    navigator = navigator,
                    qrScanner = qrScanner,
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
                    subscriptionFetchUseCase = subscriptionFetchUseCase,
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
    updateAppState: ((AppState) -> AppState) -> Unit,
    navigator: Navigator,
    qrScanner: ProxyServerQrScanUseCase,
    clipboard: Clipboard,
    tipNotifier: AndroidToastTipNotifier,
    scope: CoroutineScope,
    messages: ProxyServerListMessages,
    resultKey: String,
) {
    when (action) {
        ProxyServerListAddAction.ScanQrCode -> {
            scope.launch {
                runCatching { qrScanner.scan() }
                    .onSuccess { scanText ->
                        if (scanText.isNullOrBlank()) return@onSuccess
                        importProxyServers(
                            text = scanText,
                            groupState = groupState,
                            proxyListState = proxyListState,
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
                    groupState = groupState,
                    proxyListState = proxyListState,
                    updateAppState = updateAppState,
                    tipNotifier = tipNotifier,
                    messages = messages,
                )
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

private suspend fun importProxyServers(
    text: String,
    groupState: ProxyServerListGroups,
    proxyListState: ProxyServerListState,
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
        startServerId = proxyListState.nextProxyServerId,
        groupId = targetGroupId,
    )
    if (importResult.servers.isNotEmpty()) {
        updateAppState { state -> state.withImportedProxyServers(importResult) }
    }
    tipNotifier.show(
        messages.clipboardImportResultTemplate.formatTemplate(
            "urlCount" to importResult.urlCount,
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
    subscriptionFetchUseCase: SubscriptionFetchUseCase,
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

        ProxyServerListToolAction.UpdateSubscriptions -> {
            updateSubscriptionGroups(
                proxyListState = proxyListState,
                stateStore = stateStore,
                updateAppState = updateAppState,
                subscriptionFetchUseCase = subscriptionFetchUseCase,
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
    subscriptionFetchUseCase: SubscriptionFetchUseCase,
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
            subscriptionFetchUseCase = subscriptionFetchUseCase,
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
