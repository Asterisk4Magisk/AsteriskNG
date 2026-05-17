@file:OptIn(ExperimentalScrollBarApi::class)

package features.routing

import app.LocalIsWideScreen
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalUpdateAppState
import app.collectAppState
import app.proxyServerOutboundTag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import features.routing.model.RouteRule
import app.R
import ui.components.NavigationIcon
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.VerticalScrollBar
import top.yukonga.miuix.kmp.basic.rememberScrollBarAdapter
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import ui.layout.AdaptiveTopAppBar
import features.proxy.server.display.displayNameById
import features.proxy.server.display.displayNameWithGroup
import ui.text.formatTemplate
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.layout.pageScrollModifiers
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi

private val DomainStrategyOptions = listOf(
    "AsIs",
    "IPIfNonMatch",
    "IPOnDemand",
)

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
    val unknownGroup = stringResource(R.string.common_unknown_group)
    val defaultGroupName = stringResource(R.string.subscription_default_group)
    val defaultNodeTemplate = stringResource(R.string.routing_default_node)
    val deletedTemplate = stringResource(R.string.routing_deleted)
    val savedTemplate = stringResource(R.string.routing_saved)
    val fixedOutboundOptions = fixedRouteRuleOutboundOptions()
    val outboundOptions = remember(
        fixedOutboundOptions,
        appState.proxyServers,
        appState.subscriptionGroups,
        unknownGroup,
        defaultGroupName,
        defaultNodeTemplate,
    ) {
        val groupNames = appState.subscriptionGroups.displayNameById(defaultGroupName)
        (fixedOutboundOptions + appState.proxyServers.map { node ->
            RouteRuleOutboundOption(
                tag = node.proxyServerOutboundTag(),
                label = node.displayNameWithGroup(
                    defaultNodeTemplate = defaultNodeTemplate,
                    groupNames = groupNames,
                    unknownGroupName = unknownGroup,
                ),
            )
        }).distinctBy { option -> option.tag }
    }
    val outboundLabels = remember(outboundOptions) {
        outboundOptions.associate { option -> option.tag to option.label }
    }

    var editingRule by remember { mutableStateOf<RouteRule?>(null) }
    var showRuleDialog by remember { mutableStateOf(false) }
    val rules = appState.routeRules

    Scaffold(
        topBar = {
            AdaptiveTopAppBar(
                title = stringResource(R.string.routing_title),
                isWideScreen = isWideScreen,
                scrollBehavior = topAppBarScrollBehavior,
                actions = {
                    NavigationIcon(
                        onClick = {
                            editingRule = null
                            showRuleDialog = true
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
                item(key = "routing_policy") {
                    SmallTitle(text = stringResource(R.string.routing_domain_policy))
                    RoutingPolicyCard(
                        domainStrategyOptions = DomainStrategyOptions,
                        selectedDomainStrategy = appState.routeDomainStrategy,
                        onDomainStrategyChange = { index ->
                            updateAppState { state -> state.copy(routeDomainStrategy = index) }
                        },
                    )
                }
                item(key = "routing_rules_title") {
                    SmallTitle(text = stringResource(R.string.routing_title))
                }
                items(
                    items = rules,
                    key = { it.id },
                ) { rule ->
                    RouteRuleCard(
                        rule = rule,
                        outboundLabel = outboundLabels[rule.outboundTag] ?: rule.outboundTag,
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
                    )
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
}

@Composable
private fun fixedRouteRuleOutboundOptions(): List<RouteRuleOutboundOption> {
    return listOf(
        RouteRuleOutboundOption(tag = "proxy", label = stringResource(R.string.routing_outbound_proxy)),
        RouteRuleOutboundOption(tag = "direct", label = stringResource(R.string.routing_outbound_direct)),
        RouteRuleOutboundOption(tag = "block", label = stringResource(R.string.routing_outbound_block)),
    )
}
