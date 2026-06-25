// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

@file:OptIn(ExperimentalFoundationApi::class, ExperimentalScrollBarApi::class)

package features.routing

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import app.DefaultRouteOutboundTag
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalUpdateAppState
import app.R
import app.collectAppState
import app.proxyServerOutboundTag
import features.proxy.server.display.displayNameById
import features.proxy.server.display.displayNameWithGroup
import features.proxy.server.model.isCustomProxyServer
import features.routing.model.RouteRule
import features.routing.usecase.RouteRuleClipboardItem
import features.routing.usecase.applyRouteRuleClipboardImport
import features.routing.usecase.decodeRouteRulesFromClipboard
import features.routing.usecase.encodeRouteRulesForClipboard
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi
import ui.clipboard.ClipboardImportException
import ui.clipboard.ClipboardImportFailure
import ui.clipboard.ClipboardImportMode
import ui.clipboard.getPlainText
import ui.clipboard.setPlainText
import ui.components.IconDropdownMenu
import ui.components.IconDropdownMenuEntry
import ui.components.ImportModeDialog
import ui.components.NavigationIcon
import ui.components.longPressReorderDragHandle
import ui.components.moveItem
import ui.components.rememberAsteriskReorderableLazyListState
import ui.components.rememberReorderableLazyListContentPaddingWithoutTop
import ui.components.rememberReorderableScrollThresholdPadding
import ui.layout.AdaptiveTopAppBar
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.layout.pageScrollModifiers
import ui.text.formatTemplate

private val DomainStrategyOptions = listOf(
    "AsIs",
    "IPIfNonMatch",
    "IPOnDemand",
)

private const val RoutingPolicyItemKey = "routing_policy"
private const val RoutingRulesTitleItemKey = "routing_rules_title"
private const val RouteRuleListHeaderItemCount = 2

private enum class RoutingClipboardAction {
    Import,
    Export,
}

@Composable
fun RoutingPage(
    padding: PaddingValues,
) {
    val isWideScreen = LocalIsWideScreen.current
    val appState by LocalAppStateStore.current.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val tipNotifier = LocalAppServices.current.tipNotifier
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val unknownGroup = stringResource(R.string.common_unknown_group)
    val defaultGroupName = stringResource(R.string.subscription_default_group)
    val defaultProxyServerTemplate = stringResource(R.string.routing_default_proxy_server)
    val deletedTemplate = stringResource(R.string.routing_deleted)
    val savedTemplate = stringResource(R.string.routing_saved)
    val copiedMessage = stringResource(R.string.common_copied)
    val clipboardEmptyMessage = stringResource(R.string.common_clipboard_empty)
    val unsupportedClipboardMessage = stringResource(R.string.common_clipboard_unsupported_format)
    val routeImportTitle = stringResource(R.string.routing_import_clipboard_title)
    val routeImportMessageTemplate = stringResource(R.string.routing_import_clipboard_message)
    val routeImportedTemplate = stringResource(R.string.routing_imported)
    val routeExportEmptyMessage = stringResource(R.string.routing_export_empty)
    val noValidRouteRulesMessage = stringResource(R.string.routing_import_no_valid_rules)
    val fixedOutboundOptions = fixedRouteRuleOutboundOptions()
    val outboundOptions = remember(
        fixedOutboundOptions,
        appState.proxyServers,
        appState.subscriptionGroups,
        unknownGroup,
        defaultGroupName,
        defaultProxyServerTemplate,
    ) {
        val groupNames = appState.subscriptionGroups.displayNameById(defaultGroupName)
        (fixedOutboundOptions + appState.proxyServers.filterNot { proxyServer ->
            proxyServer.server.isCustomProxyServer()
        }.map { proxyServer ->
            RouteRuleOutboundOption(
                tag = proxyServer.proxyServerOutboundTag(),
                label = proxyServer.displayNameWithGroup(
                    defaultProxyServerTemplate = defaultProxyServerTemplate,
                    groupNames = groupNames,
                    unknownGroupName = unknownGroup,
                ),
            )
        }).distinctBy { option -> option.tag }
    }
    val outboundLabels = remember(outboundOptions) {
        outboundOptions.associate { option -> option.tag to option.label }
    }
    val defaultOutboundTag = appState.defaultRouteOutboundTag.effectiveDefaultOutboundTag(outboundOptions)
    val defaultOutboundLabel = outboundLabels[defaultOutboundTag] ?: defaultOutboundTag

    var editingRule by remember { mutableStateOf<RouteRule?>(null) }
    var showRuleDialog by remember { mutableStateOf(false) }
    var pendingRouteImport by remember { mutableStateOf<List<RouteRuleClipboardItem>?>(null) }
    val rules = appState.routeRules

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = stringResource(R.string.routing_title),
                subtitle = stringResource(R.string.routing_default_outbound_subtitle)
                    .formatTemplate("tag" to defaultOutboundLabel),
                isWideScreen = isWideScreen,
                scrollBehavior = topAppBarScrollBehavior,
                actions = {
                    RoutingDefaultOutboundMenu(
                        outboundOptions = outboundOptions,
                        selectedTag = defaultOutboundTag,
                        onSelectedTagChange = { tag ->
                            updateAppState { state -> state.copy(defaultRouteOutboundTag = tag) }
                        },
                    )
                    NavigationIcon(
                        onClick = {
                            editingRule = null
                            showRuleDialog = true
                        },
                        imageVector = MiuixIcons.Add,
                    )
                    RoutingClipboardMenu { action ->
                        when (action) {
                            RoutingClipboardAction.Import -> {
                                scope.launch {
                                    runCatching {
                                        decodeRouteRulesFromClipboard(clipboard.getPlainText().orEmpty())
                                    }.onSuccess { importedRules ->
                                        pendingRouteImport = importedRules
                                    }.onFailure { error ->
                                        tipNotifier.show(
                                            error.routeRuleClipboardImportMessage(
                                                emptyClipboard = clipboardEmptyMessage,
                                                unsupportedFormat = unsupportedClipboardMessage,
                                                noValidRules = noValidRouteRulesMessage,
                                            ),
                                        )
                                    }
                                }
                            }

                            RoutingClipboardAction.Export -> {
                                scope.launch {
                                    if (rules.isEmpty()) {
                                        tipNotifier.show(routeExportEmptyMessage)
                                    } else {
                                        clipboard.setPlainText(encodeRouteRulesForClipboard(rules))
                                        tipNotifier.show(copiedMessage)
                                    }
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        val contentPadding = pageContentPaddingWithCutout(
            innerPadding = innerPadding,
            outerPadding = padding,
            isWideScreen = isWideScreen,
        )
        val listPadding = pageListPadding(contentPadding)
        val lazyListState = rememberLazyListState()
        val listBottomPadding = listPadding.calculateBottomPadding()
        val lazyContentPadding = rememberReorderableLazyListContentPaddingWithoutTop(listPadding)
        val reorderableLazyListState = rememberAsteriskReorderableLazyListState(
            lazyListState = lazyListState,
            itemCount = rules.size,
            itemIndexOffset = RouteRuleListHeaderItemCount,
            scrollThresholdPadding = rememberReorderableScrollThresholdPadding(
                bottom = listBottomPadding,
            ),
        ) { fromRuleIndex, toRuleIndex ->
            updateAppState { state ->
                val reorderedRules = state.routeRules.moveItem(
                    fromIndex = fromRuleIndex,
                    toIndex = toRuleIndex,
                )
                if (reorderedRules === state.routeRules) {
                    state
                } else {
                    state.copy(routeRules = reorderedRules)
                }
            }
        }

        Box {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .padding(top = listPadding.calculateTopPadding())
                    .pageScrollModifiers(topAppBarScrollBehavior),
                contentPadding = lazyContentPadding,
            ) {
                item(key = RoutingPolicyItemKey) {
                    SmallTitle(text = stringResource(R.string.routing_domain_policy))
                    RoutingPolicyCard(
                        domainStrategyOptions = DomainStrategyOptions,
                        selectedDomainStrategy = appState.routeDomainStrategy,
                        onDomainStrategyChange = { index ->
                            updateAppState { state -> state.copy(routeDomainStrategy = index) }
                        },
                    )
                }
                item(key = RoutingRulesTitleItemKey) {
                    SmallTitle(text = stringResource(R.string.routing_title))
                }
                itemsIndexed(
                    items = rules,
                    key = { _, rule -> rule.id },
                ) { _, rule ->
                    ReorderableItem(reorderableLazyListState.reorderableState, key = rule.id) { isDragging ->
                        RouteRuleCard(
                            rule = rule,
                            outboundLabel = outboundLabels[rule.outboundTag] ?: rule.outboundTag,
                            isDragging = isDragging,
                            dragModifier = Modifier.longPressReorderDragHandle(
                                scope = this,
                                enabled = rules.size > 1,
                                state = reorderableLazyListState,
                            ),
                            onToggle = { enabled ->
                                updateAppState { state ->
                                    state.copy(
                                        routeRules = state.routeRules.map {
                                            if (it.id == rule.id) it.copy(enabled = enabled) else it
                                        },
                                    )
                                }
                            },
                            onEdit = {
                                editingRule = rule
                                showRuleDialog = true
                            },
                            onDelete = {
                                updateAppState { state ->
                                    state.copy(
                                        routeRules = state.routeRules.filterNot { it.id == rule.id },
                                    )
                                }
                                scope.launch {
                                    tipNotifier.show(deletedTemplate.formatTemplate("name" to rule.remarks))
                                }
                            },
                            modifier = Modifier.animateItem(
                                fadeInSpec = null,
                                fadeOutSpec = null,
                                placementSpec = folmeSpring(damping = 0.9f, response = 0.38f),
                            ),
                        )
                    }
                }
                item(key = "routing_empty") {
                    if (rules.isEmpty()) {
                        RoutingEmptyCard()
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

    RouteRuleEditorBottomSheet(
        show = showRuleDialog,
        initialRule = editingRule,
        nextRuleId = appState.nextRouteRuleId,
        outboundOptions = outboundOptions,
        onDismissRequest = { showRuleDialog = false },
        onSave = { savedRule ->
            updateAppState { state ->
                val exists = state.routeRules.any { it.id == savedRule.id }
                state.copy(
                    routeRules = if (exists) {
                        state.routeRules.map { if (it.id == savedRule.id) savedRule else it }
                    } else {
                        state.routeRules + savedRule
                    },
                    nextRouteRuleId = if (exists) {
                        state.nextRouteRuleId
                    } else {
                        maxOf(state.nextRouteRuleId, savedRule.id + 1)
                    },
                )
            }
            showRuleDialog = false
            scope.launch {
                tipNotifier.show(savedTemplate.formatTemplate("name" to savedRule.remarks))
            }
        },
    )

    val routeImport = pendingRouteImport
    ImportModeDialog(
        show = routeImport != null,
        title = routeImportTitle,
        message = routeImportMessageTemplate.formatTemplate("count" to routeImport.orEmpty().size),
        onDismissRequest = { pendingRouteImport = null },
        onModeSelected = { importMode ->
            val importedRules = pendingRouteImport ?: return@ImportModeDialog
            var importedCount = 0
            updateAppState { state ->
                val result = applyRouteRuleClipboardImport(
                    existingRules = state.routeRules,
                    importedRules = importedRules,
                    nextRuleId = state.nextRouteRuleId,
                    mode = importMode,
                )
                importedCount = when (importMode) {
                    ClipboardImportMode.Replace -> result.rules.size
                    ClipboardImportMode.Merge -> (result.rules.size - state.routeRules.size).coerceAtLeast(0)
                }
                state.copy(
                    routeRules = result.rules,
                    nextRouteRuleId = result.nextRuleId,
                )
            }
            pendingRouteImport = null
            scope.launch {
                tipNotifier.show(routeImportedTemplate.formatTemplate("count" to importedCount))
            }
        },
    )
}

@Composable
private fun fixedRouteRuleOutboundOptions(): List<RouteRuleOutboundOption> {
    return listOf(
        RouteRuleOutboundOption(tag = DefaultRouteOutboundTag, label = stringResource(R.string.routing_outbound_proxy)),
        RouteRuleOutboundOption(tag = "direct", label = stringResource(R.string.routing_outbound_direct)),
        RouteRuleOutboundOption(tag = "block", label = stringResource(R.string.routing_outbound_block)),
    )
}

@Composable
private fun RoutingDefaultOutboundMenu(
    outboundOptions: List<RouteRuleOutboundOption>,
    selectedTag: String,
    onSelectedTagChange: (String) -> Unit,
) {
    IconDropdownMenu(
        imageVector = MiuixIcons.Tune,
        contentDescription = stringResource(R.string.routing_default_outbound_tag),
        entries = outboundOptions.map { option ->
            IconDropdownMenuEntry(
                key = option.tag,
                title = option.label,
                selected = option.tag == selectedTag,
                action = option.tag,
            )
        },
        onAction = onSelectedTagChange,
    )
}

private fun String.effectiveDefaultOutboundTag(outboundOptions: List<RouteRuleOutboundOption>): String {
    val normalizedTag = trim().ifBlank { DefaultRouteOutboundTag }
    return if (outboundOptions.any { option -> option.tag == normalizedTag }) {
        normalizedTag
    } else {
        DefaultRouteOutboundTag
    }
}

@Composable
private fun RoutingClipboardMenu(
    onAction: (RoutingClipboardAction) -> Unit,
) {
    IconDropdownMenu(
        imageVector = MiuixIcons.More,
        contentDescription = stringResource(R.string.common_more),
        entries = listOf(
            IconDropdownMenuEntry(
                key = "import-clipboard",
                title = stringResource(R.string.common_import_from_clipboard),
                action = RoutingClipboardAction.Import,
            ),
            IconDropdownMenuEntry(
                key = "export-clipboard",
                title = stringResource(R.string.common_export_to_clipboard),
                action = RoutingClipboardAction.Export,
            ),
        ),
        onAction = onAction,
    )
}

private fun Throwable.routeRuleClipboardImportMessage(
    emptyClipboard: String,
    unsupportedFormat: String,
    noValidRules: String,
): String {
    return when ((this as? ClipboardImportException)?.failure) {
        ClipboardImportFailure.EmptyClipboard -> emptyClipboard
        ClipboardImportFailure.NoValidRoutingRules -> noValidRules
        ClipboardImportFailure.UnsupportedFormat -> unsupportedFormat
        else -> unsupportedFormat
    }
}
