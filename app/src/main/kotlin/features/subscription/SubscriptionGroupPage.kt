@file:OptIn(ExperimentalScrollBarApi::class)

package features.subscription

import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.LocalUpdateAppState
import app.SubscriptionGroupState
import app.collectAppState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.R
import ui.components.BackNavigationIcon
import ui.components.NavigationIcon
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import ui.layout.AdaptiveTopAppBar
import features.proxy.server.display.displayName
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.layout.pageScrollModifiers
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi

@Composable
fun SubscriptionGroupPage(
    padding: PaddingValues,
    groupId: Int?,
) {
    val isWideScreen = LocalIsWideScreen.current
    val navigator = LocalNavigator.current
    val appState by LocalAppStateStore.current.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val initialGroup = appState.subscriptionGroups.firstOrNull { it.id == groupId }
    val isEditing = initialGroup != null
    val builtIn = initialGroup?.builtIn == true

    val newGroupName = stringResource(R.string.subscription_new_group)
    val defaultGroupName = stringResource(R.string.subscription_default_group)
    val unnamedGroupName = stringResource(R.string.subscription_unnamed_group)

    var name by remember(groupId, newGroupName, defaultGroupName, builtIn) {
        mutableStateOf(
            when {
                builtIn -> initialGroup.displayName(defaultGroupName)
                else -> initialGroup?.name ?: newGroupName
            },
        )
    }
    var url by remember(groupId) { mutableStateOf(initialGroup?.url ?: "") }
    var userAgent by remember(groupId) { mutableStateOf(initialGroup?.userAgent ?: DefaultSubscriptionUserAgent) }
    var interval by remember(groupId) { mutableStateOf(initialGroup?.updateInterval?.filter(Char::isDigit).orEmpty()) }
    var updateViaProxy by remember(groupId) { mutableStateOf(initialGroup?.updateViaProxy ?: false) }

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = if (isEditing) stringResource(R.string.subscription_edit) else stringResource(R.string.subscription_add),
                subtitle = if (builtIn) stringResource(R.string.subscription_default_group) else stringResource(R.string.subscription_group),
                isWideScreen = isWideScreen,
                scrollBehavior = topAppBarScrollBehavior,
                navigationIcon = {
                    BackNavigationIcon(
                        onClick = { navigator.pop() },
                    )
                },
                actions = {
                    NavigationIcon(
                        onClick = {
                            updateAppState { state ->
                                val existing = groupId?.let { id ->
                                    state.subscriptionGroups.firstOrNull { it.id == id }
                                }
                                if (existing != null) {
                                    val savedUrl = url.trim()
                                    val savedGroup = SubscriptionGroupState(
                                        id = existing.id,
                                        name = if (existing.builtIn) existing.name else name.trim().ifBlank { unnamedGroupName },
                                        url = savedUrl,
                                        userAgent = userAgent.trim().ifBlank { DefaultSubscriptionUserAgent },
                                        updateInterval = interval.trim().takeIf { savedUrl.isNotBlank() }.orEmpty(),
                                        updateViaProxy = updateViaProxy && savedUrl.isNotBlank(),
                                        enabled = existing.enabled,
                                        builtIn = existing.builtIn,
                                        lastUpdatedAtMillis = existing.lastUpdatedAtMillis,
                                    )
                                    state.copy(
                                        subscriptionGroups = state.subscriptionGroups.map {
                                            if (it.id == existing.id) savedGroup else it
                                        },
                                    )
                                } else {
                                    val savedUrl = url.trim()
                                    val savedGroup = SubscriptionGroupState(
                                        id = state.nextSubscriptionGroupId,
                                        name = name.trim().ifBlank { unnamedGroupName },
                                        url = savedUrl,
                                        userAgent = userAgent.trim().ifBlank { DefaultSubscriptionUserAgent },
                                        updateInterval = interval.trim().takeIf { savedUrl.isNotBlank() }.orEmpty(),
                                        updateViaProxy = updateViaProxy && savedUrl.isNotBlank(),
                                        enabled = true,
                                    )
                                    state.copy(
                                        subscriptionGroups = state.subscriptionGroups + savedGroup,
                                        nextSubscriptionGroupId = state.nextSubscriptionGroupId + 1,
                                    )
                                }
                            }
                            navigator.pop()
                        },
                        imageVector = MiuixIcons.Ok,
                    )
                },
            )
        },
    ) { innerPadding ->
        val lazyListState = rememberLazyListState()
        val contentPadding = pageContentPaddingWithCutout(
            innerPadding = innerPadding,
            outerPadding = padding,
            isWideScreen = isWideScreen,
        )
        val listPadding = pageListPadding(contentPadding)

        Box {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.pageScrollModifiers(
                    topAppBarScrollBehavior,
                ),
                contentPadding = listPadding,
            ) {
                item(key = "subscription_editor") {
                    SmallTitle(text = stringResource(R.string.subscription_section))
                    SubscriptionGroupForm(
                        name = name,
                        url = url,
                        userAgent = userAgent,
                        interval = interval,
                        updateViaProxy = updateViaProxy,
                        builtIn = builtIn,
                        onNameChange = { name = it },
                        onUrlChange = { url = it },
                        onUserAgentChange = { userAgent = it },
                        onIntervalChange = { interval = it },
                        onUpdateViaProxyChange = { updateViaProxy = it },
                    )
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
