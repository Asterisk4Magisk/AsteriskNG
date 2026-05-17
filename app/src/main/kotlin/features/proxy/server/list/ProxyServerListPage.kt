package features.proxy.server.list

import app.LocalAppStateStore
import app.LocalAppServices
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import app.ProxyServerState
import app.R
import app.collectProxyServerListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import features.subscription.DefaultSubscriptionGroupId
import engine.proxy.latency.ProxyServerLatencyTestMode
import features.proxy.server.usecase.ProxyServiceResult
import features.proxy.server.usecase.restartProxyServiceAfterSelection
import features.proxy.server.usecase.runProxyServerLatencyTest
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding

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
    val subscriptionFetchUseCase = services.subscriptionFetchUseCase
    val proxyEngine = services.proxyEngine
    val proxyServiceUseCase = services.proxyServiceUseCase
    val tipNotifier = services.tipNotifier
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val serviceRestartMutex = remember { Mutex() }

    var searchValue by rememberSaveable { mutableStateOf("") }
    var selectedGroupId by rememberSaveable { mutableStateOf(DefaultSubscriptionGroupId) }
    var serviceOperationInProgress by rememberSaveable { mutableStateOf(false) }
    val servers = proxyListState.proxyServers
    var selectedServerId by rememberSaveable { mutableIntStateOf(proxyListState.selectedProxyServerId) }
    val selectedServer = servers.firstOrNull { server -> server.id == selectedServerId }
    val proxyRunning = proxyListState.proxyRunning
    val allGroupName = stringResource(R.string.proxy_server_list_all)
    val defaultGroupName = stringResource(R.string.subscription_default_group)
    val unknownGroupName = stringResource(R.string.common_unknown_group)
    val messages = proxyServerListMessages()

    LaunchedEffect(proxyListState.selectedProxyServerId) {
        selectedServerId = proxyListState.selectedProxyServerId
    }

    fun runProxyServiceOperation(operation: suspend () -> Unit) {
        if (serviceOperationInProgress) return
        scope.launch {
            serviceOperationInProgress = true
            try {
                operation()
            } finally {
                serviceOperationInProgress = false
            }
        }
    }

    fun restartProxyServiceSilently(serverId: Int) {
        restartProxyServiceAfterSelection(
            serverId = serverId,
            scope = scope,
            serviceRestartMutex = serviceRestartMutex,
            stateStore = stateStore,
            proxyEngine = proxyEngine,
            updateAppState = updateAppState,
        )
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
            scope = scope,
            stateStore = stateStore,
            updateAppState = updateAppState,
            proxyLatencyTester = proxyLatencyTester,
            tipNotifier = tipNotifier,
            noTestableServersMessage = messages.noTestableServers,
            latencyResultTemplate = messages.latencyResultTemplate,
            latencyFailedMessage = messages.latencyFailed,
        )
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
    val groupPagerState = rememberPagerState(
        initialPage = groupState.selectedTabIndex,
        pageCount = { groupTabIds.size.coerceAtLeast(1) },
    )

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
                selectedServer = selectedServer,
                proxyListState = proxyListState,
                stateStore = stateStore,
                updateAppState = updateAppState,
                navigator = navigator,
                qrScanner = qrScanner,
                subscriptionFetchUseCase = subscriptionFetchUseCase,
                proxyServiceUseCase = proxyServiceUseCase,
                clipboard = clipboard,
                tipNotifier = tipNotifier,
                scope = scope,
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
        val isImeVisible = WindowInsets.ime.asPaddingValues().calculateBottomPadding() > 0.dp
        val safeDrawingBottomPadding = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
        val floatingToolbarBottomPadding = if (isImeVisible) {
            if (isWideScreen) {
                0.dp
            } else {
                (contentPadding.calculateBottomPadding() - safeDrawingBottomPadding).coerceAtLeast(0.dp)
            }
        } else {
            contentPadding.calculateBottomPadding()
        }
        val listPadding = pageListPadding(contentPadding, bottomExtra = 128.dp)

        Box {
            ProxyServerListPager(
                groupPagerState = groupPagerState,
                groupState = groupState,
                searchValue = searchValue,
                servers = servers,
                selectedServerId = selectedServerId,
                unknownGroupName = unknownGroupName,
                itemTextFormatter = itemTextFormatter,
                topAppBarScrollBehavior = topAppBarScrollBehavior,
                listPadding = listPadding,
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
                onRestartProxyServiceSilently = ::restartProxyServiceSilently,
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
                                updateAppState { state -> state.copy(proxyRunning = result.proxyRunning) }
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
}
