// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import app.ProxyServerState
import app.R
import app.collectProxyServerListState
import engine.proxy.latency.ProxyServerLatencyTestMode
import features.proxy.server.usecase.ProxyServiceResult
import features.proxy.server.usecase.restartProxyServiceAfterSelection
import features.proxy.server.usecase.runProxyServerLatencyTest
import features.subscription.DefaultSubscriptionGroupId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.components.DeleteConfirmationDialog
import ui.text.formatTemplate

private const val ProxyServerEditResultKey = "proxy-server-edit-result"

@Composable
fun ProxyServerListPage(
    padding: PaddingValues,
) {
    val isWideScreen = LocalIsWideScreen.current
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val stateStore = LocalAppStateStore.current
    val proxyListState by stateStore.collectProxyServerListState()
    val updateAppState = LocalUpdateAppState.current
    val navigator = LocalNavigator.current
    val services = LocalAppServices.current
    val proxyLatencyTester = services.proxyLatencyTester
    val qrScanner = services.qrScanner
    val subscriptionFetcher = services.subscriptionFetcher
    val proxyEngine = services.proxyEngine
    val proxyServiceUseCase = services.proxyServiceUseCase
    val proxyServerImportFileUseCase = services.proxyServerImportFileUseCase
    val tipNotifier = services.tipNotifier
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val serviceRestartMutex = remember { Mutex() }

    var searchValue by rememberSaveable { mutableStateOf("") }
    // Derive the initial selectedGroupId from the persisted selectedProxyServerId
    // so the tab starts on the correct group after process death. rememberSaveable
    // cannot survive process kill, so we compute the initial value from proxyListState
    // (synchronously loaded from persistent storage) instead of defaulting to
    // DefaultSubscriptionGroupId.
    val initialSelectedGroupId = remember {
        proxyListState.proxyServers
            .firstOrNull { it.id == proxyListState.selectedProxyServerId }
            ?.groupId
            ?: DefaultSubscriptionGroupId
    }
    var selectedGroupId by rememberSaveable { mutableIntStateOf(initialSelectedGroupId) }
    var serviceOperationInProgress by rememberSaveable { mutableStateOf(false) }
    var pendingProxyServerDeletion by remember { mutableStateOf<ProxyServerState?>(null) }
    val servers = proxyListState.proxyServers
    var selectedServerId by rememberSaveable { mutableIntStateOf(proxyListState.selectedProxyServerId) }
    val selectedServer = servers.firstOrNull { server -> server.id == selectedServerId }
    val proxyRunning = proxyListState.proxyRunning
    val allGroupName = stringResource(R.string.proxy_server_list_all)
    val defaultGroupName = stringResource(R.string.subscription_default_group)
    val unknownGroupName = stringResource(R.string.common_unknown_group)
    val messages = proxyServerListMessages()
    val columns = proxyListState.proxyServerListLayout.resolvedProxyServerListColumns()

    LaunchedEffect(proxyListState.selectedProxyServerId) {
        selectedServerId = proxyListState.selectedProxyServerId
    }

    fun runProxyServiceOperation(operation: suspend () -> Unit) {
        if (serviceOperationInProgress) return
        serviceOperationInProgress = true
        services.appScope.launch {
            try {
                operation()
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    serviceOperationInProgress = false
                }
            }
        }
    }

    fun restartProxyServiceSilently(serverId: Int) {
        restartProxyServiceAfterSelection(
            serverId = serverId,
            scope = services.appScope,
            serviceRestartMutex = serviceRestartMutex,
            stateStore = stateStore,
            proxyEngine = proxyEngine,
            updateAppState = updateAppState,
        )
    }

    LaunchedEffect(stateStore, proxyEngine) {
        var previousServerId = stateStore.state.value.selectedProxyServerId
        stateStore.state
            .map { state -> state.selectedProxyServerId }
            .distinctUntilChanged()
            .collect { serverId ->
                if (serverId == previousServerId) {
                    return@collect
                }
                previousServerId = serverId
                restartProxyServiceSilently(serverId)
            }
    }

    fun testProxyServerLatency(
        targetServers: List<ProxyServerState>,
        mode: ProxyServerLatencyTestMode,
        doneTemplate: String,
        showSingleResult: Boolean = false,
    ) {
        runProxyServerLatencyTest(
            targetServers = targetServers,
            mode = mode,
            doneTemplate = doneTemplate,
            showSingleResult = showSingleResult,
            scope = services.appScope,
            stateStore = stateStore,
            updateAppState = updateAppState,
            proxyLatencyTester = proxyLatencyTester,
            tipNotifier = tipNotifier,
            noTestableServersMessage = messages.noTestableServers,
            latencyResultTemplate = messages.latencyResultTemplate,
            latencyFailedMessage = messages.latencyFailed,
        )
    }

    fun deleteProxyServer(server: ProxyServerState) {
        if (serviceOperationInProgress) return
        val remarks = server.server.getInfo().remarks

        fun removeServer(stopResult: ProxyServiceResult.Success? = null): Boolean {
            var deleted = false
            updateAppState { state ->
                val nextServers = state.proxyServers.filterNot { it.id == server.id }
                if (nextServers.size == state.proxyServers.size) {
                    stopResult?.let { result ->
                        state.copy(
                            proxyRunning = result.proxyRunning,
                            localProxyPort = result.appState?.localProxyPort ?: state.localProxyPort,
                        )
                    } ?: state
                } else {
                    deleted = true
                    val selectedProxyServerId = if (state.selectedProxyServerId == server.id) {
                        nextServers.firstOrNull()?.id ?: state.selectedProxyServerId
                    } else {
                        state.selectedProxyServerId
                    }
                    state.copy(
                        proxyServers = nextServers,
                        selectedProxyServerId = selectedProxyServerId,
                        proxyRunning = stopResult?.proxyRunning ?: state.proxyRunning,
                        localProxyPort = stopResult?.appState?.localProxyPort ?: state.localProxyPort,
                    )
                }
            }
            return deleted
        }

        val stateSnapshot = stateStore.state.value
        if (stateSnapshot.selectedProxyServerId != server.id || !stateSnapshot.proxyRunning) {
            if (removeServer()) {
                services.appScope.launch {
                    tipNotifier.show(messages.deletedTemplate.formatTemplate("name" to remarks))
                }
            }
            return
        }

        runProxyServiceOperation {
            when (val stopResult = proxyServiceUseCase.stop(stateStore.state.value.runMode)) {
                is ProxyServiceResult.Success -> {
                    if (removeServer(stopResult)) {
                        tipNotifier.show(messages.deletedTemplate.formatTemplate("name" to remarks))
                    }
                }

                ProxyServiceResult.MissingServer -> {
                    tipNotifier.show(messages.selectServerFirst)
                }

                is ProxyServiceResult.Failed -> {
                    updateAppState { state -> state.copy(proxyRunning = false) }
                    tipNotifier.showError(stopResult.error, messages.serviceStopped)
                }
            }
        }
    }

    fun requestProxyServerDeletion(server: ProxyServerState) {
        if (proxyListState.enableDeletionConfirmation) {
            pendingProxyServerDeletion = server
        } else {
            deleteProxyServer(server)
        }
    }

    ProxyServerEditResultHandler(
        navigator = navigator,
        resultKey = ProxyServerEditResultKey,
        messages = messages,
        updateAppState = updateAppState,
        tipNotifier = tipNotifier,
        onSelectedGroupIdChange = { selectedGroupId = it },
    )

    val groupState = proxyServerListGroups(
        state = proxyListState,
        selectedGroupId = selectedGroupId,
        searchValue = searchValue,
        allGroupName = allGroupName,
        defaultGroupName = defaultGroupName,
    )
    val itemTextFormatter = rememberProxyServerListItemTextFormatter(
        groupNames = groupState.groupNames,
        unknownGroupName = unknownGroupName,
    )
    val groupTabIds = groupState.groupTabs.map { group -> group.id }
    val groupPagerState = key(groupTabIds) {
        rememberPagerState(
            initialPage = groupState.selectedTabIndex,
            pageCount = { groupTabIds.size.coerceAtLeast(1) },
        )
    }
    val groupPagerOffsetFraction by remember(groupPagerState) {
        derivedStateOf { groupPagerState.currentPageOffsetFraction }
    }

    LaunchedEffect(groupTabIds) {
        val lastIndex = groupTabIds.lastIndex
        if (lastIndex >= 0 && groupPagerState.currentPage > lastIndex) {
            groupPagerState.scrollToPage(lastIndex)
        }
    }

    LaunchedEffect(groupState.selectedTabIndex, groupTabIds) {
        if (
            groupTabIds.isNotEmpty() &&
            !groupPagerState.isScrollInProgress &&
            groupPagerState.currentPage != groupState.selectedTabIndex
        ) {
            groupPagerState.animateScrollToPage(groupState.selectedTabIndex)
        }
    }

    LaunchedEffect(groupPagerState, groupTabIds) {
        snapshotFlow { groupPagerState.targetPage }
            .collect { page ->
                groupState.groupTabs.getOrNull(page)?.let { group ->
                    if (selectedGroupId != group.id) {
                        selectedGroupId = group.id
                    }
                }
            }
    }

    Scaffold(
        topBar = {
            ProxyServerListTopBar(
                isWideScreen = isWideScreen,
                scrollBehavior = topAppBarScrollBehavior,
                searchValue = searchValue,
                onSearchValueChange = { searchValue = it },
                groupState = groupState,
                groupPagerPage = groupPagerState.currentPage,
                groupPagerOffsetFraction = groupPagerOffsetFraction,
                selectedServer = selectedServer,
                proxyListState = proxyListState,
                stateStore = stateStore,
                updateAppState = updateAppState,
                navigator = navigator,
                qrScanner = qrScanner,
                proxyServerImportFileUseCase = proxyServerImportFileUseCase,
                subscriptionFetcher = subscriptionFetcher,
                proxyServiceUseCase = proxyServiceUseCase,
                clipboard = clipboard,
                tipNotifier = tipNotifier,
                scope = scope,
                backgroundScope = services.appScope,
                messages = messages,
                resultKey = ProxyServerEditResultKey,
                serviceOperationInProgress = serviceOperationInProgress,
                runProxyServiceOperation = ::runProxyServiceOperation,
                onSelectedGroupIdChange = { selectedGroupId = it },
                onTestProxyServerLatency = ::testProxyServerLatency,
            )
        },
    ) { innerPadding ->
        val contentPadding = pageContentPaddingWithCutout(
            innerPadding = innerPadding,
            outerPadding = padding,
            isWideScreen = isWideScreen,
        )
        val floatingToolbarBottomPadding = contentPadding.calculateBottomPadding()
        val listPadding = pageListPadding(
            contentPadding = contentPadding,
            bottomExtra = ProxyServerListFloatingToolbarReservedBottomPadding,
        )
        val dragScrollThresholdBottomPadding = pageListPadding(contentPadding).calculateBottomPadding()

        Box {
            ProxyServerListPager(
                groupPagerState = groupPagerState,
                groupState = groupState,
                searchValue = searchValue,
                servers = servers,
                selectedServerId = selectedServerId,
                columns = columns,
                sort = proxyListState.proxyServerListSort,
                unknownGroupName = unknownGroupName,
                itemTextFormatter = itemTextFormatter,
                topAppBarScrollBehavior = topAppBarScrollBehavior,
                listPadding = listPadding,
                dragScrollThresholdBottomPadding = dragScrollThresholdBottomPadding,
                contentPadding = contentPadding,
                stateStore = stateStore,
                updateAppState = updateAppState,
                navigator = navigator,
                clipboard = clipboard,
                tipNotifier = tipNotifier,
                scope = scope,
                messages = messages,
                resultKey = ProxyServerEditResultKey,
                onSelectedServerIdChange = { selectedServerId = it },
                onDeleteServer = ::requestProxyServerDeletion,
            )
            ProxyServerListFloatingToolbar(
                running = proxyRunning,
                serviceOperationInProgress = serviceOperationInProgress,
                bottomPadding = floatingToolbarBottomPadding,
                onToggleRunning = {
                    runProxyServiceOperation {
                        when (
                            val result = proxyServiceUseCase.toggle(
                                state = stateStore.state.value,
                                selectedServer = selectedServer,
                            )
                        ) {
                            is ProxyServiceResult.Success -> {
                                updateAppState { state ->
                                    state.copy(
                                        proxyRunning = result.proxyRunning,
                                        localProxyPort = result.appState?.localProxyPort ?: state.localProxyPort,
                                    )
                                }
                                tipNotifier.show(
                                    if (result.proxyRunning) messages.serviceStarted else messages.serviceStopped,
                                )
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
                },
                onRealConnectionTest = {
                    val server = servers.firstOrNull { it.id == selectedServerId }
                    if (server == null) {
                        scope.launch {
                            tipNotifier.show(messages.selectServerFirst)
                        }
                    } else {
                        testProxyServerLatency(
                            targetServers = listOf(server),
                            mode = ProxyServerLatencyTestMode.RealConnection,
                            doneTemplate = messages.realConnectionDoneTemplate,
                            showSingleResult = true,
                        )
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }

    pendingProxyServerDeletion?.let { server ->
        DeleteConfirmationDialog(
            show = true,
            title = stringResource(R.string.deletion_confirmation_delete_proxy_server),
            onDismissRequest = { pendingProxyServerDeletion = null },
            onConfirm = {
                pendingProxyServerDeletion = null
                deleteProxyServer(server)
            },
        )
    }
}
