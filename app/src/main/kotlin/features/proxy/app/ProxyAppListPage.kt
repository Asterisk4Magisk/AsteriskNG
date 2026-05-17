@file:OptIn(ExperimentalScrollBarApi::class)

package features.proxy.app

import app.LocalAppStateStore
import app.LocalAppServices
import app.LocalIsWideScreen
import app.LocalUpdateAppState
import app.modes.RunModeVpnService
import app.collectAppState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.R
import system.ANDROID_APP_ICON_SIZE_DP
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.theme.MiuixTheme
import ui.layout.AdaptiveTopAppBar
import features.proxy.app.model.ProxyAppListItem
import features.proxy.app.model.ProxyAppListUserSpaceTabUi
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.layout.pageScrollModifiers
import androidx.compose.runtime.getValue
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi

@Composable
fun ProxyAppListPage(
    padding: PaddingValues,
) {
    val pageState = rememberProxyAppListPageState()
    val appState by LocalAppStateStore.current.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val isWideScreen = LocalIsWideScreen.current
    val services = LocalAppServices.current
    val packageCatalog = services.packageCatalog
    val userSpaces = services.userSpaces
    val tipNotifier = services.tipNotifier
    val topAppBarScrollBehavior = MiuixScrollBehavior()

    val proxyAppListModes = proxyAppListModeLabels()
    val modeIndex = appState.proxyAppListMode.coerceIn(proxyAppListModes.indices)
    val isVpnServiceMode = appState.runMode == RunModeVpnService
    val selectedAppKeys = remember(appState.proxyAppListSelectedApps) {
        appState.proxyAppListSelectedApps.toSet()
    }
    val vpnServiceUserId = if (isVpnServiceMode) {
        pageState.userSpaces.firstOrNull()?.id
    } else {
        null
    }
    val userTabIds = remember(pageState.userTabs) {
        pageState.userTabs.map { tab -> tab.id }
    }
    val selectedUserIndex = remember(pageState.userTabs, pageState.selectedUserId) {
        pageState.userTabs.indexOfFirst { tab -> tab.id == pageState.selectedUserId }
            .coerceAtLeast(0)
    }
    val userPagerState = rememberPagerState(
        initialPage = selectedUserIndex,
        pageCount = { userTabIds.size.coerceAtLeast(1) },
    )
    val iconSizePx = with(LocalDensity.current) {
        ANDROID_APP_ICON_SIZE_DP.dp.roundToPx()
    }

    ProxyAppListPageEffects(
        pageState = pageState,
        selectedAppKeys = selectedAppKeys,
        isVpnServiceMode = isVpnServiceMode,
        vpnServiceUserId = vpnServiceUserId,
        selectedUserIndex = selectedUserIndex,
        userTabIds = userTabIds,
        userPagerState = userPagerState,
        packageCatalog = packageCatalog,
        userSpaces = userSpaces,
        tipNotifier = tipNotifier,
    )

    Scaffold(
        topBar = {
            ProxyAppListTopBar(
                modes = proxyAppListModes,
                modeIndex = modeIndex,
                isWideScreen = isWideScreen,
                scrollBehavior = topAppBarScrollBehavior,
                searchValue = pageState.searchValue,
                showSystemApps = pageState.showSystemApps,
                userTabs = pageState.userTabs,
                selectedUserId = pageState.selectedUserId,
                onModeChanged = { index ->
                    updateAppState { state -> state.copy(proxyAppListMode = index) }
                },
                onSearchValueChange = { value -> pageState.searchValue = value },
                onShowSystemAppsChange = { enabled -> pageState.showSystemApps = enabled },
                onSelectedUserIdChange = { userId -> pageState.selectedUserId = userId },
            )
        },
    ) { innerPadding ->
        val contentPadding = pageContentPaddingWithCutout(
            innerPadding = innerPadding,
            outerPadding = padding,
            isWideScreen = isWideScreen,
        )
        val listPadding = pageListPadding(contentPadding)

        ProxyAppListContent(
            pageState = pageState,
            selectedAppKeys = selectedAppKeys,
            modeIndex = modeIndex,
            iconSizePx = iconSizePx,
            contentPadding = contentPadding,
            listPadding = listPadding,
            scrollBehavior = topAppBarScrollBehavior,
            userPagerState = userPagerState,
            onAppCheckedChange = { item, isChecked ->
                updateAppState { state ->
                    state.copy(
                        proxyAppListSelectedApps = updateProxyAppListSelection(
                            selectedApps = state.proxyAppListSelectedApps,
                            item = item,
                            isChecked = isChecked,
                        ),
                    )
                }
            },
        )
    }
}

@Composable
private fun ProxyAppListTopBar(
    modes: List<String>,
    modeIndex: Int,
    isWideScreen: Boolean,
    scrollBehavior: ScrollBehavior,
    searchValue: String,
    showSystemApps: Boolean,
    userTabs: List<ProxyAppListUserSpaceTabUi>,
    selectedUserId: Int?,
    onModeChanged: (Int) -> Unit,
    onSearchValueChange: (String) -> Unit,
    onShowSystemAppsChange: (Boolean) -> Unit,
    onSelectedUserIdChange: (Int) -> Unit,
) {
    AdaptiveTopAppBar(
        title = stringResource(R.string.proxy_app_list_title),
        subtitle = modes[modeIndex],
        isWideScreen = isWideScreen,
        scrollBehavior = scrollBehavior,
        actions = {
            ProxyAppListModeMenu(
                modes = modes,
                selectedIndex = modeIndex,
                onSelectedIndexChange = onModeChanged,
            )
            ProxyAppListDisplayOptionsMenu(
                showSystemApps = showSystemApps,
                onShowSystemAppsChange = onShowSystemAppsChange,
            )
        },
        bottomContent = {
            Column {
                ProxyAppListSearchBar(
                    searchValue = searchValue,
                    onSearchValueChange = onSearchValueChange,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                )
                if (userTabs.size > 1) {
                    ProxyAppListUserSpaceTabs(
                        tabs = userTabs,
                        selectedUserId = selectedUserId,
                        onSelectedUserIdChange = onSelectedUserIdChange,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun ProxyAppListContent(
    pageState: ProxyAppListPageState,
    selectedAppKeys: Set<String>,
    modeIndex: Int,
    iconSizePx: Int,
    contentPadding: PaddingValues,
    listPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    userPagerState: PagerState,
    onAppCheckedChange: (ProxyAppListItem, Boolean) -> Unit,
) {
    Box {
        PullToRefresh(
            isRefreshing = pageState.loadingApps,
            onRefresh = pageState::requestRefresh,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            contentPadding = listPadding,
            topAppBarScrollBehavior = scrollBehavior,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            refreshTexts = listOf(
                stringResource(R.string.proxy_app_list_pull_to_refresh),
                stringResource(R.string.proxy_app_list_release_to_refresh),
                stringResource(R.string.proxy_app_list_refreshing),
                stringResource(R.string.proxy_app_list_refreshed),
            ),
        ) {
            HorizontalPager(
                state = userPagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 0,
                verticalAlignment = Alignment.Top,
            ) { page ->
                ProxyAppListUserPage(
                    userId = pageState.userTabs.getOrNull(page)?.id,
                    pageState = pageState,
                    selectedAppKeys = selectedAppKeys,
                    modeIndex = modeIndex,
                    iconSizePx = iconSizePx,
                    contentPadding = contentPadding,
                    listPadding = listPadding,
                    scrollBehavior = scrollBehavior,
                    onAppCheckedChange = onAppCheckedChange,
                )
            }
        }
    }
}

@Composable
private fun ProxyAppListUserPage(
    userId: Int?,
    pageState: ProxyAppListPageState,
    selectedAppKeys: Set<String>,
    modeIndex: Int,
    iconSizePx: Int,
    contentPadding: PaddingValues,
    listPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    onAppCheckedChange: (ProxyAppListItem, Boolean) -> Unit,
) {
    val visibleApps = userId?.let { id ->
        pageState.preparedAppListData.visibleItemsByUser[id]
    }.orEmpty()
    val lazyListState = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.pageScrollModifiers(scrollBehavior),
            contentPadding = listPadding,
        ) {
            when {
                visibleApps.isEmpty() -> item(key = "app_empty", contentType = "empty") {
                    ProxyAppListEmptyState()
                }

                else -> items(
                    items = visibleApps,
                    key = { item -> item.key },
                    contentType = { "app" },
                ) { item ->
                    val checked = remember(item.selectionKeys, selectedAppKeys) {
                        item.selectionKeys.any { key -> key in selectedAppKeys }
                    }
                    ProxyAppListItemCard(
                        app = item.app,
                        checked = checked,
                        enabled = modeIndex != ProxyAppListGlobalModeIndex,
                        sharedUid = item.sharedUid,
                        iconSizePx = iconSizePx,
                        onCheckedChange = { isChecked ->
                            onAppCheckedChange(item, isChecked)
                        },
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

@Composable
private fun proxyAppListModeLabels(): List<String> {
    return listOf(
        stringResource(R.string.proxy_app_list_mode_blacklist),
        stringResource(R.string.proxy_app_list_mode_whitelist),
        stringResource(R.string.proxy_app_list_mode_global),
    )
}
