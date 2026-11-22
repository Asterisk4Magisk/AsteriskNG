// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(ExperimentalScrollBarApi::class)

package features.proxy.server.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalNavigator
import app.R
import app.collectAppState
import app.displayTitle
import app.navigation.ProxyServerEditResult
import features.proxy.server.display.displayName
import features.proxy.server.display.displayNameById
import features.proxy.server.display.displayNameWithGroup
import features.proxy.server.model.Custom
import features.proxy.server.model.ProxyServer
import features.proxy.server.model.ProxyServerValidationIssue
import features.proxy.server.model.isCompositeProxyServer
import features.proxy.server.model.isCustomProxyServer
import features.proxy.server.usecase.ProxyServerCopyTextResult
import features.proxy.server.usecase.proxyServerCopyText
import features.proxy.server.usecase.withSavedProxyServer
import features.proxy.server.validation.rememberProxyServerValidationMessageResolver
import features.subscription.DefaultSubscriptionGroupId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import ui.clipboard.setPlainText
import ui.components.BackNavigationIcon
import ui.components.NavigationIcon
import ui.components.WarningConfirmDialog
import ui.layout.AdaptiveTopAppBar
import ui.layout.EditorListItem
import ui.layout.EditorListPane
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageReadableWidth
import ui.layout.pageScrollModifiers
import ui.layout.rememberCodeEditorFocusState

@Composable
fun ProxyServerPage(
    padding: PaddingValues,
    ps: ProxyServer<*>,
    serverId: Int? = null,
    groupId: Int? = null,
    returnGroupId: Int? = null,
    resultKey: String? = null,
) {
    val isWideScreen = LocalIsWideScreen.current
    val stateStore = LocalAppStateStore.current
    val appState by stateStore.collectAppState()
    var activeServerId by rememberSaveable(serverId) { mutableStateOf(serverId) }
    val topAppBarScrollBehavior = key(activeServerId) { MiuixScrollBehavior() }
    val navigator = LocalNavigator.current
    val tipNotifier = LocalAppServices.current.tipNotifier
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val validationMessageOf = rememberProxyServerValidationMessageResolver()
    val copiedMessage = stringResource(R.string.common_copied)
    val unsupportedMessage = stringResource(R.string.common_unsupported)
    val invalidConfigMessage = stringResource(R.string.proxy_server_config_invalid)
    val fullValidationWarningTitle = stringResource(R.string.proxy_editor_full_validation_warning_title)
    val fullValidationWarningSummary = stringResource(R.string.proxy_editor_full_validation_warning_summary)
    val continueSaveMessage = stringResource(R.string.proxy_editor_continue_save)
    val returnEditMessage = stringResource(R.string.proxy_editor_return_edit)
    val unknownGroupName = stringResource(R.string.common_unknown_group)
    val defaultGroupName = stringResource(R.string.subscription_default_group)
    val allGroupsLabel = stringResource(R.string.proxy_editor_strategy_group_all_groups)
    val defaultProxyServerTemplate = stringResource(R.string.routing_default_proxy_server)
    val saveFailedMessage = stringResource(R.string.editor_switch_save_failed)
    val targetUnavailableMessage = stringResource(R.string.editor_switch_target_unavailable)

    val editingEntry = remember(activeServerId) {
        stateStore.state.value.proxyServers.firstOrNull { it.id == activeServerId }
    }
    val initialProxy = editingEntry?.server ?: ps
    val serverDraft = remember(activeServerId, initialProxy) {
        initialProxy.editableCopy()
    }
    val configJsonState = remember(activeServerId, serverDraft) {
        (serverDraft as? Custom)?.let { JsonCodeEditorState(it.configJson) }
    }
    val editorFocus = key(activeServerId) {
        rememberCodeEditorFocusState(
            editorFocused = configJsonState?.isFocused == true,
            clearEditorFocus = { configJsonState?.clearFocus() },
        )
    }
    var pendingSwitchId by remember(activeServerId) { mutableStateOf<Int?>(null) }
    var pendingValidation by remember(activeServerId) { mutableStateOf<PendingProxyValidation?>(null) }
    var saving by remember(activeServerId) { mutableStateOf(false) }

    fun syncCustomDraft() {
        if (serverDraft is Custom && configJsonState != null) serverDraft.configJson = configJsonState.snapshotText()
    }
    fun switchTo(targetId: Int) {
        if (stateStore.state.value.proxyServers.none { it.id == targetId }) {
            scope.launch { tipNotifier.show(targetUnavailableMessage) }
            return
        }
        editorFocus.exit()
        activeServerId = targetId
    }
    fun saveProxyServer(switchTargetId: Int?) {
        val current = editingEntry
        if (switchTargetId != null && current != null) {
            val draft = serverDraft.editableCopy()
            pendingSwitchId = switchTargetId
            saving = true
            scope.launch {
                var applied = false
                try {
                    stateStore.update { state ->
                        if (state.proxyServers.none { it.id == current.id && it.server == current.server }) {
                            return@update state
                        }
                        applied = true
                        state.withSavedProxyServer(current.id, draft, current.groupId).state
                    }
                    if (applied) {
                        stateStore.awaitPersistence()
                        switchTo(switchTargetId)
                    } else {
                        tipNotifier.show(saveFailedMessage)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    stateStore.update { state ->
                        if (state.proxyServers.any { it.id == current.id && it.server === draft }) {
                            state.withSavedProxyServer(current.id, current.server, current.groupId).state
                        } else {
                            state
                        }
                    }
                    tipNotifier.show(saveFailedMessage)
                } finally {
                    pendingSwitchId = null
                    saving = false
                }
            }
        } else if (resultKey != null && activeServerId != null) {
            navigator.setResult(
                resultKey,
                ProxyServerEditResult(
                    serverId = requireNotNull(activeServerId),
                    server = serverDraft,
                    groupId = current?.groupId ?: groupId,
                    returnGroupId = returnGroupId,
                ),
            )
        } else {
            ps.update(serverDraft)
            navigator.pop()
        }
    }
    fun validateAndSave(switchTargetId: Int? = null) {
        if (saving) return
        syncCustomDraft()
        val basicIssue = serverDraft.validateBasic().firstOrNull()
        if (basicIssue != null) {
            pendingSwitchId = null
            scope.launch { tipNotifier.show(validationMessageOf(basicIssue)) }
            return
        }
        val fullIssues = serverDraft.validateFull()
        if (fullIssues.isNotEmpty()) {
            pendingSwitchId = null
            pendingValidation = PendingProxyValidation(fullIssues, switchTargetId)
        } else {
            saveProxyServer(switchTargetId)
        }
    }
    val groupOptions = remember(appState.subscriptionGroups, allGroupsLabel, defaultGroupName) {
        listOf(ProxyServerEditorGroupOption(null, allGroupsLabel)) +
            appState.subscriptionGroups
                .filter { group -> group.enabled || group.builtIn || group.id == DefaultSubscriptionGroupId }
                .map { group ->
                    ProxyServerEditorGroupOption(
                        id = group.id,
                        label = group.displayName(defaultGroupName).ifBlank { defaultGroupName },
                    )
                }
    }
    val memberOptions = remember(appState.proxyServers, appState.subscriptionGroups, activeServerId, unknownGroupName, defaultGroupName) {
        val groupNames = appState.subscriptionGroups.displayNameById(defaultGroupName)
        appState.proxyServers
            .filter { server ->
                server.id != activeServerId &&
                    !server.server.isCompositeProxyServer() &&
                    !server.server.isCustomProxyServer()
            }
            .map { server ->
                ProxyServerEditorMemberOption(
                    id = server.id,
                    label = server.displayNameWithGroup(
                        defaultProxyServerTemplate = defaultProxyServerTemplate,
                        groupNames = groupNames,
                        unknownGroupName = unknownGroupName,
                    ),
                )
            }
    }
    val editorOptions = remember(groupOptions, memberOptions) {
        ProxyServerEditorOptions(
            groupOptions = groupOptions,
            memberOptions = memberOptions,
        )
    }
    val title = serverDraft.editorTitle()

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = title,
                isWideScreen = isWideScreen,
                scrollBehavior = topAppBarScrollBehavior,
                navigationIcon = {
                    BackNavigationIcon(
                        onClick = {
                            if (!saving) {
                                if (editorFocus.expanded) editorFocus.exit() else navigator.pop()
                            }
                        },
                    )
                },
                actions = {
                    NavigationIcon(
                        onClick = {
                            if (saving) return@NavigationIcon
                            syncCustomDraft()
                            scope.launch {
                                val basicIssues = serverDraft.validateBasic()
                                if (basicIssues.isNotEmpty()) {
                                    tipNotifier.show(validationMessageOf(basicIssues.first()))
                                    return@launch
                                }
                                when (
                                    val result = serverDraft.proxyServerCopyText(
                                        context = context,
                                        appState = appState,
                                        serverId = activeServerId,
                                        groupId = editingEntry?.groupId ?: groupId,
                                    )
                                ) {
                                    is ProxyServerCopyTextResult.Success -> {
                                        clipboard.setPlainText(result.text)
                                        tipNotifier.show(copiedMessage)
                                    }

                                    ProxyServerCopyTextResult.Unsupported -> {
                                        tipNotifier.show(unsupportedMessage)
                                    }

                                    ProxyServerCopyTextResult.InvalidConfig -> {
                                        tipNotifier.show(invalidConfigMessage)
                                    }
                                }
                            }
                        },
                        imageVector = MiuixIcons.Copy,
                    )
                    NavigationIcon(
                        onClick = { validateAndSave() },
                        imageVector = MiuixIcons.Ok,
                    )
                },
            )
        },
    ) { innerPadding ->
        val contentPadding = pageContentPaddingWithCutout(
            innerPadding = innerPadding,
            outerPadding = padding,
            isWideScreen = isWideScreen,
        )
        val editorGroup = groupId ?: editingEntry?.groupId
        val entries = remember(appState.proxyServers, editorGroup) {
            appState.proxyServers.filter { editorGroup == null || it.groupId == editorGroup }
                .map { EditorListItem(it.id, it.displayTitle()) }
        }
        EditorListPane(
            title = stringResource(R.string.proxy_server_list_title),
            entries = entries,
            selectedId = activeServerId,
            padding = contentPadding,
            show = !editorFocus.expanded,
            onSelect = if (resultKey != null) {
                select@{ targetId ->
                    if (saving || targetId == activeServerId) return@select
                    syncCustomDraft()
                    if (serverDraft != initialProxy) {
                        editorFocus.exit()
                        pendingSwitchId = targetId
                    } else {
                        switchTo(targetId)
                    }
                }
            } else {
                null
            },
            selectionEnabled = !saving,
        ) {
            key(activeServerId) {
                val lazyListState = rememberLazyListState()
                if (serverDraft is Custom) {
                    CustomProxyServerEditor(
                        customEdit = serverDraft,
                        configJsonState = requireNotNull(configJsonState),
                        editorExpanded = editorFocus.expanded,
                        contentPadding = contentPadding,
                    )
                } else {
                    Box(Modifier.pageReadableWidth(maxWidth = 800.dp)) {
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier.pageScrollModifiers(
                                topAppBarScrollBehavior,
                            ),
                            contentPadding = contentPadding,
                        ) {
                            proxyServerEditorContent(serverDraft, editorOptions)
                        }
                        VerticalScrollBar(
                            adapter = rememberScrollBarAdapter(lazyListState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            trackPadding = contentPadding,
                        )
                    }
                }
            }
        }
    }

    WindowDialog(
        show = pendingSwitchId != null,
        title = stringResource(R.string.editor_switch_unsaved_title),
        onDismissRequest = { if (!saving) pendingSwitchId = null },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.editor_switch_unsaved_message),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (editingEntry != null) {
                TextButton(
                    text = stringResource(R.string.editor_switch_save),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !saving,
                    onClick = { pendingSwitchId?.let(::validateAndSave) },
                )
            }
            TextButton(
                text = stringResource(R.string.editor_switch_discard),
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving,
                onClick = {
                    pendingSwitchId?.let { targetId ->
                        pendingSwitchId = null
                        switchTo(targetId)
                    }
                },
            )
            TextButton(
                text = stringResource(R.string.editor_switch_cancel),
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving,
                onClick = { pendingSwitchId = null },
            )
        }
    }

    ProxyServerFullValidationWarningDialog(
        show = pendingValidation != null,
        title = fullValidationWarningTitle,
        summary = fullValidationWarningSummary,
        issueMessages = pendingValidation?.issues.orEmpty().map(validationMessageOf),
        returnEditText = returnEditMessage,
        continueSaveText = continueSaveMessage,
        onDismissRequest = { pendingValidation = null },
        onContinueSave = {
            pendingValidation?.let { validation ->
                pendingValidation = null
                saveProxyServer(validation.switchTargetId)
            }
        },
    )
}

private data class PendingProxyValidation(
    val issues: List<ProxyServerValidationIssue>,
    val switchTargetId: Int?,
)

@Composable
private fun ProxyServerFullValidationWarningDialog(
    show: Boolean,
    title: String,
    summary: String,
    issueMessages: List<String>,
    returnEditText: String,
    continueSaveText: String,
    onDismissRequest: () -> Unit,
    onContinueSave: () -> Unit,
) {
    val warningColor = MiuixTheme.colorScheme.error
    WarningConfirmDialog(
        show = show,
        title = title,
        summary = summary,
        dismissText = returnEditText,
        confirmText = continueSaveText,
        onDismissRequest = onDismissRequest,
        onConfirm = onContinueSave,
    ) {
        issueMessages.distinct().forEachIndexed { index, message ->
            if (index > 0) {
                Spacer(Modifier.height(10.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 7.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(warningColor),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = message,
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onBackground,
                )
            }
        }
    }
}
