// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.R
import features.proxy.app.usecase.ProxyAppListClipboardData
import features.proxy.app.usecase.applyProxyAppListClipboardImport
import features.proxy.app.usecase.decodeProxyAppListFromClipboard
import features.proxy.app.usecase.encodeProxyAppListForClipboard
import kotlinx.coroutines.launch
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
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import ui.clipboard.ClipboardImportException
import ui.clipboard.ClipboardImportFailure
import ui.clipboard.ClipboardImportMode
import ui.clipboard.getPlainText
import ui.clipboard.setPlainText
import ui.components.ImportModeDialog
import ui.text.formatTemplate

@Composable
fun ProxyAppListPage(
    padding: PaddingValues,
) {
    val pageState = rememberProxyAppListPageState()
    val appState by LocalAppStateStore.current.collectAppState()
    val selfPackageName = LocalContext.current.applicationContext.packageName
    val updateAppState = LocalUpdateAppState.current
    val isWideScreen = LocalIsWideScreen.current
    val services = LocalAppServices.current
    val packageCatalog = services.packageCatalog
    val userSpaces = services.userSpaces
    val tipNotifier = services.tipNotifier
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.common_copied)
    val clipboardEmptyMessage = stringResource(R.string.common_clipboard_empty)
    val unsupportedClipboardMessage = stringResource(R.string.common_clipboard_unsupported_format)
    val importTitle = stringResource(R.string.proxy_app_list_import_clipboard_title)
    val importMessageTemplate = stringResource(R.string.proxy_app_list_import_clipboard_message)
    val importedTemplate = stringResource(R.string.proxy_app_list_imported)
    val noValidAppsMessage = stringResource(R.string.proxy_app_list_import_no_valid_apps)
    val invalidEntryMessage = stringResource(R.string.proxy_app_list_import_invalid_entry)
    val invalidUserIdMessage = stringResource(R.string.proxy_app_list_import_invalid_user)
    val unsupportedModeMessage = stringResource(R.string.proxy_app_list_import_unsupported_mode)
    var pendingAppListImport by remember { mutableStateOf<ProxyAppListClipboardData?>(null) }

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
    val selectedUserId = pageState.selectedUserId
        ?.takeIf { userId -> userId in userTabIds }
        ?: userTabIds.firstOrNull()
    val selectedUserIndex = remember(userTabIds, selectedUserId) {
        userTabIds.indexOf(selectedUserId)
            .coerceAtLeast(0)
    }
    val userPagerState = key(userTabIds) {
        rememberPagerState(
            initialPage = selectedUserIndex,
            pageCount = { userTabIds.size.coerceAtLeast(1) },
        )
    }
    val iconSizePx = with(LocalDensity.current) {
        ANDROID_APP_ICON_SIZE_DP.dp.roundToPx()
    }

    LaunchedEffect(selfPackageName, appState.proxyAppListSelectedApps) {
        val prunedSelection = appState.proxyAppListSelectedApps.filterNot { key ->
            key == selfPackageName || key.substringAfter(':') == selfPackageName
        }
        if (prunedSelection != appState.proxyAppListSelectedApps) {
            updateAppState { state -> state.copy(proxyAppListSelectedApps = prunedSelection) }
        }
    }

    ProxyAppListPageEffects(
        pageState = pageState,
        selectedAppKeys = selectedAppKeys,
        isVpnServiceMode = isVpnServiceMode,
        vpnServiceUserId = vpnServiceUserId,
        selfPackageName = selfPackageName,
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
                selectedUserId = selectedUserId,
                onModeChanged = { index ->
                    updateAppState { state -> state.copy(proxyAppListMode = index) }
                },
                onSearchValueChange = { value -> pageState.searchValue = value },
                onMoreAction = { action ->
                    when (action) {
                        ProxyAppListMoreAction.ToggleSystemApps -> {
                            pageState.showSystemApps = !pageState.showSystemApps
                        }

                        ProxyAppListMoreAction.ImportClipboard -> {
                            scope.launch {
                                runCatching {
                                    decodeProxyAppListFromClipboard(
                                        text = clipboard.getPlainText().orEmpty(),
                                        currentUserId = selectedUserId ?: 0,
                                        selfPackageName = selfPackageName,
                                    )
                                }.onSuccess { imported ->
                                    pendingAppListImport = imported
                                }.onFailure { error ->
                                    tipNotifier.show(
                                        error.proxyAppListClipboardImportMessage(
                                            emptyClipboard = clipboardEmptyMessage,
                                            unsupportedFormat = unsupportedClipboardMessage,
                                            noValidApps = noValidAppsMessage,
                                            invalidEntry = invalidEntryMessage,
                                            invalidUserId = invalidUserIdMessage,
                                            unsupportedMode = unsupportedModeMessage,
                                        ),
                                    )
                                }
                            }
                        }

                        ProxyAppListMoreAction.ExportClipboard -> {
                            scope.launch {
                                clipboard.setPlainText(
                                    encodeProxyAppListForClipboard(
                                        selectedApps = appState.proxyAppListSelectedApps,
                                        mode = appState.proxyAppListMode,
                                    ),
                                )
                                tipNotifier.show(copiedMessage)
                            }
                        }
                    }
                },
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

    val appListImport = pendingAppListImport
    ImportModeDialog(
        show = appListImport != null,
        title = importTitle,
        message = importMessageTemplate.formatTemplate("count" to appListImport?.selectedApps.orEmpty().size),
        onDismissRequest = { pendingAppListImport = null },
        onModeSelected = { importMode ->
            val imported = pendingAppListImport ?: return@ImportModeDialog
            var importedCount = 0
            updateAppState { state ->
                val result = applyProxyAppListClipboardImport(
                    currentMode = state.proxyAppListMode,
                    currentSelectedApps = state.proxyAppListSelectedApps,
                    imported = imported,
                    mode = importMode,
                )
                importedCount = when (importMode) {
                    ClipboardImportMode.Replace -> result.selectedApps.size
                    ClipboardImportMode.Merge ->
                        (result.selectedApps.size - state.proxyAppListSelectedApps.size).coerceAtLeast(0)
                }
                state.copy(
                    proxyAppListMode = result.mode,
                    proxyAppListSelectedApps = result.selectedApps,
                )
            }
            pendingAppListImport = null
            scope.launch {
                tipNotifier.show(importedTemplate.formatTemplate("count" to importedCount))
            }
        },
    )
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
    onMoreAction: (ProxyAppListMoreAction) -> Unit,
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
            ProxyAppListMoreActionsMenu(
                showSystemApps = showSystemApps,
                onAction = onMoreAction,
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

private fun Throwable.proxyAppListClipboardImportMessage(
    emptyClipboard: String,
    unsupportedFormat: String,
    noValidApps: String,
    invalidEntry: String,
    invalidUserId: String,
    unsupportedMode: String,
): String {
    return when ((this as? ClipboardImportException)?.failure) {
        ClipboardImportFailure.EmptyClipboard -> emptyClipboard
        ClipboardImportFailure.NoValidApps -> noValidApps
        ClipboardImportFailure.InvalidAppEntry -> invalidEntry
        ClipboardImportFailure.InvalidAppUserId -> invalidUserId
        ClipboardImportFailure.UnsupportedAppMode -> unsupportedMode
        ClipboardImportFailure.UnsupportedFormat -> unsupportedFormat
        else -> unsupportedFormat
    }
}
