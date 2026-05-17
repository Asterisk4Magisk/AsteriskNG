@file:OptIn(ExperimentalFoundationApi::class)

package features.proxy.app

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import system.AndroidPackageProvider
import system.AndroidUserSpaceProvider
import ui.feedback.AndroidToastTipNotifier
import system.user.AndroidUserSpace
import androidx.compose.ui.res.stringResource
import features.proxy.app.model.AppPackageEntry
import features.proxy.app.model.ProxyAppListItem
import features.proxy.app.model.ProxyAppListPreparedData
import features.proxy.app.model.ProxyAppListUserSpaceTabUi
import features.proxy.app.model.loadProxyAppListPackages
import features.proxy.app.model.prepareProxyAppListData
import features.proxy.app.model.sortedForProxyAppListRefresh
import features.proxy.app.model.toAndroidUserSpace
import features.proxy.app.model.toUserSpaceTabs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ExperimentalFoundationApi

@Composable
internal fun rememberProxyAppListPageState(): ProxyAppListPageState {
    return remember { ProxyAppListPageState() }
}

@Stable
internal class ProxyAppListPageState {
    var userSpaces by mutableStateOf<List<AndroidUserSpace>>(emptyList())
    var selectedUserId by mutableStateOf<Int?>(null)
    var appPackages by mutableStateOf<List<AppPackageEntry>>(emptyList())
    var loadingApps by mutableStateOf(false)
    var loadedPackageFilterKey by mutableStateOf<Pair<Boolean, Boolean>?>(null)
    var refreshSeed by mutableStateOf(0)
    var preparedAppListData by mutableStateOf(ProxyAppListPreparedData.Empty)
    var userTabs by mutableStateOf(emptyList<ProxyAppListUserSpaceTabUi>())
    var searchValue by mutableStateOf("")
    var debouncedSearchValue by mutableStateOf("")
    var showSystemApps by mutableStateOf(false)

    fun requestRefresh() {
        if (!loadingApps) {
            loadingApps = true
            refreshSeed += 1
        }
    }
}

@Composable
internal fun ProxyAppListPageEffects(
    pageState: ProxyAppListPageState,
    selectedAppKeys: Set<String>,
    isVpnServiceMode: Boolean,
    vpnServiceUserId: Int?,
    selectedUserIndex: Int,
    userTabIds: List<Int>,
    userPagerState: PagerState,
    packageCatalog: AndroidPackageProvider,
    userSpaces: AndroidUserSpaceProvider,
    tipNotifier: AndroidToastTipNotifier,
) {
    ProxyAppListSearchEffect(pageState)
    ProxyAppListPreparedDataEffect(
        pageState = pageState,
        selectedAppKeys = selectedAppKeys,
        isVpnServiceMode = isVpnServiceMode,
        vpnServiceUserId = vpnServiceUserId,
    )
    ProxyAppListPagerEffect(
        pageState = pageState,
        selectedUserIndex = selectedUserIndex,
        userTabIds = userTabIds,
        userPagerState = userPagerState,
    )
    ProxyAppListUserSpaceEffect(
        pageState = pageState,
        isVpnServiceMode = isVpnServiceMode,
        userSpaces = userSpaces,
    )
    ProxyAppListPackageEffect(
        pageState = pageState,
        selectedAppKeys = selectedAppKeys,
        isVpnServiceMode = isVpnServiceMode,
        packageCatalog = packageCatalog,
        tipNotifier = tipNotifier,
    )
}

internal fun updateProxyAppListSelection(
    selectedApps: List<String>,
    item: ProxyAppListItem,
    isChecked: Boolean,
): List<String> {
    return if (isChecked) {
        (selectedApps + item.selectionKeys).distinct()
    } else {
        selectedApps - item.selectionKeys.toSet()
    }
}

internal const val ProxyAppListGlobalModeIndex = 2

@Composable
private fun ProxyAppListSearchEffect(
    pageState: ProxyAppListPageState,
) {
    LaunchedEffect(pageState.searchValue) {
        delay(150)
        pageState.debouncedSearchValue = pageState.searchValue
    }
}

@Composable
private fun ProxyAppListPreparedDataEffect(
    pageState: ProxyAppListPageState,
    selectedAppKeys: Set<String>,
    isVpnServiceMode: Boolean,
    vpnServiceUserId: Int?,
) {
    LaunchedEffect(
        pageState.userSpaces,
        pageState.appPackages,
        pageState.debouncedSearchValue,
        isVpnServiceMode,
        vpnServiceUserId,
    ) {
        pageState.preparedAppListData = prepareProxyAppListData(
            userSpaces = pageState.userSpaces,
            appPackages = pageState.appPackages,
            searchValue = pageState.debouncedSearchValue,
            fixedUserId = if (isVpnServiceMode) vpnServiceUserId else null,
        )
    }

    LaunchedEffect(pageState.preparedAppListData, selectedAppKeys) {
        pageState.userTabs = pageState.preparedAppListData.toUserSpaceTabs(selectedAppKeys)
    }
}

@Composable
private fun ProxyAppListPagerEffect(
    pageState: ProxyAppListPageState,
    selectedUserIndex: Int,
    userTabIds: List<Int>,
    userPagerState: PagerState,
) {
    LaunchedEffect(userTabIds) {
        val lastIndex = userTabIds.lastIndex
        if (lastIndex >= 0 && userPagerState.currentPage > lastIndex) {
            userPagerState.scrollToPage(lastIndex)
        }
    }

    LaunchedEffect(selectedUserIndex, userTabIds) {
        if (
            userTabIds.isNotEmpty() &&
            !userPagerState.isScrollInProgress &&
            userPagerState.currentPage != selectedUserIndex
        ) {
            userPagerState.animateScrollToPage(selectedUserIndex)
        }
    }

    LaunchedEffect(userPagerState, userTabIds) {
        snapshotFlow { userPagerState.targetPage }
            .collect { page ->
                pageState.userTabs.getOrNull(page)?.let { tab ->
                    if (pageState.selectedUserId != tab.id) {
                        pageState.selectedUserId = tab.id
                    }
                }
            }
    }
}

@Composable
private fun ProxyAppListUserSpaceEffect(
    pageState: ProxyAppListPageState,
    isVpnServiceMode: Boolean,
    userSpaces: AndroidUserSpaceProvider,
) {
    LaunchedEffect(isVpnServiceMode) {
        val fallbackCurrentUser = userSpaces.fallbackCurrentUserSpace()

        if (isVpnServiceMode) {
            pageState.userSpaces = listOf(fallbackCurrentUser)
            pageState.selectedUserId = fallbackCurrentUser.id
            return@LaunchedEffect
        }

        val loadedUsers = loadProxyAppListUserSpaces(
            userSpaces = userSpaces,
            fallbackCurrentUser = fallbackCurrentUser,
        )
        val loadedUserIds = loadedUsers.map { user -> user.id }
        pageState.userSpaces = loadedUsers
        if (pageState.selectedUserId == null || pageState.selectedUserId !in loadedUserIds) {
            pageState.selectedUserId = loadedUsers.first().id
        }
    }
}

@Composable
private fun ProxyAppListPackageEffect(
    pageState: ProxyAppListPageState,
    selectedAppKeys: Set<String>,
    isVpnServiceMode: Boolean,
    packageCatalog: AndroidPackageProvider,
    tipNotifier: AndroidToastTipNotifier,
) {
    val loadFailedMessage = stringResource(R.string.proxy_app_list_load_failed)

    LaunchedEffect(pageState.showSystemApps, pageState.refreshSeed, isVpnServiceMode) {
        val packageFilterKey = pageState.showSystemApps to isVpnServiceMode
        val replacingAppList = pageState.loadedPackageFilterKey != packageFilterKey
        val selectedAppKeysOnRefresh = selectedAppKeys

        if (replacingAppList) {
            pageState.appPackages = emptyList()
            pageState.preparedAppListData = ProxyAppListPreparedData.Empty
        }

        pageState.loadingApps = true
        try {
            withFrameNanos { }
            pageState.appPackages = packageCatalog.loadProxyAppListPackages(
                showSystemApps = pageState.showSystemApps,
                currentUserOnly = isVpnServiceMode,
            ).sortedForProxyAppListRefresh(selectedAppKeysOnRefresh)
            pageState.loadedPackageFilterKey = packageFilterKey
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            tipNotifier.show(loadFailedMessage)
            pageState.appPackages = emptyList()
        } finally {
            pageState.loadingApps = false
        }
    }
}

private suspend fun AndroidUserSpaceProvider.fallbackCurrentUserSpace(): AndroidUserSpace {
    return runCatching {
        currentAndroidUserSpace()
    }.getOrElse {
        AndroidUserSpace(id = 0, name = "User 0")
    }
}

private suspend fun loadProxyAppListUserSpaces(
    userSpaces: AndroidUserSpaceProvider,
    fallbackCurrentUser: AndroidUserSpace,
): List<AndroidUserSpace> {
    return runCatching {
        userSpaces.listAndroidUsers().map { user -> user.toAndroidUserSpace() }
    }.getOrElse {
        runCatching {
            userSpaces.listAndroidUserSpaces()
        }.getOrElse {
            listOf(fallbackCurrentUser)
        }
    }.ifEmpty {
        listOf(fallbackCurrentUser)
    }
}
