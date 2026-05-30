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
import androidx.compose.foundation.lazy.items
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
import top.yukonga.miuix.kmp.icon.extended.Add
import ui.layout.AdaptiveTopAppBar
import ui.text.formatTemplate
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.layout.pageScrollModifiers
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi

@Composable
fun SubscriptionGroupListPage(
    padding: PaddingValues,
) {
    val isWideScreen = LocalIsWideScreen.current
    val navigator = LocalNavigator.current
    val appState by LocalAppStateStore.current.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val groups = appState.subscriptionGroups
    var editingGroupId by remember { mutableStateOf<Int?>(null) }
    var showGroupEditor by remember { mutableStateOf(false) }
    val editingGroup = editingGroupId?.let { id -> groups.firstOrNull { it.id == id } }

    fun closeGroupEditor() {
        showGroupEditor = false
    }

    fun clearGroupEditor() {
        editingGroupId = null
    }

    fun saveGroup(group: SubscriptionGroupState, isNew: Boolean) {
        updateAppState { state ->
            if (isNew) {
                val savedGroup = group.copy(id = state.nextSubscriptionGroupId)
                state.copy(
                    subscriptionGroups = state.subscriptionGroups + savedGroup,
                    nextSubscriptionGroupId = state.nextSubscriptionGroupId + 1,
                )
            } else {
                state.copy(
                    subscriptionGroups = state.subscriptionGroups.map {
                        if (it.id == group.id) group else it
                    },
                )
            }
        }
    }

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = stringResource(R.string.subscription_group_list_title),
                subtitle = stringResource(R.string.subscription_group_list_count).formatTemplate("count" to groups.size),
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
                            editingGroupId = null
                            showGroupEditor = true
                        },
                        imageVector = MiuixIcons.Add,
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
                item(key = "subscription_title") {
                    SmallTitle(text = stringResource(R.string.subscription_group_list))
                }
                items(
                    items = groups,
                    key = { it.id },
                ) { group ->
                    SubscriptionGroupCard(
                        group = group,
                        onToggle = { enabled ->
                            updateAppState { state ->
                                state.copy(
                                    subscriptionGroups = state.subscriptionGroups.map {
                                        if (it.id == group.id) it.copy(enabled = enabled) else it
                                    },
                                )
                            }
                        },
                        onEdit = {
                            editingGroupId = group.id
                            showGroupEditor = true
                        },
                        onDelete = {
                            if (group.builtIn) return@SubscriptionGroupCard
                            updateAppState { state ->
                                val nextServers = state.proxyServers.filterNot { it.groupId == group.id }
                                state.copy(
                                    subscriptionGroups = state.subscriptionGroups.filterNot { it.id == group.id },
                                    proxyServers = nextServers,
                                    selectedProxyServerId = if (nextServers.any { it.id == state.selectedProxyServerId }) {
                                        state.selectedProxyServerId
                                    } else {
                                        nextServers.firstOrNull()?.id ?: 0
                                    },
                                )
                            }
                        },
                    )
                }
            }
            VerticalScrollBar(
                adapter = rememberScrollBarAdapter(lazyListState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                trackPadding = contentPadding,
            )
        }
        SubscriptionGroupEditorDialog(
            show = showGroupEditor,
            group = editingGroup,
            nextGroupId = appState.nextSubscriptionGroupId,
            onDismissRequest = ::closeGroupEditor,
            onDismissFinished = ::clearGroupEditor,
            onSave = ::saveGroup,
        )
    }
}
