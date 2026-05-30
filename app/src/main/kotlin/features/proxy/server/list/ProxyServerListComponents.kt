// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.list

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.R
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.FloatingToolbar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Stopwatch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import ui.isInDarkTheme
import ui.components.IconDropdownMenu
import ui.components.IconDropdownMenuEntry
import ui.components.draggedCardShadow

private val proxyServerLatencyNumberRegex = Regex("""\d+""")
private val ProxyServerListFloatingToolbarButtonSize = 52.dp
private val ProxyServerListFloatingToolbarVerticalPadding = 8.dp
private val ProxyServerListFloatingToolbarBottomSpacing = 16.dp
private val ProxyServerListFloatingToolbarContentGap = 12.dp
internal val ProxyServerListFloatingToolbarReservedBottomPadding =
    ProxyServerListFloatingToolbarButtonSize +
        ProxyServerListFloatingToolbarVerticalPadding +
        ProxyServerListFloatingToolbarVerticalPadding +
        ProxyServerListFloatingToolbarBottomSpacing +
        ProxyServerListFloatingToolbarContentGap

@Composable
internal fun ProxyServerListGroupTabs(
    groups: List<ProxyServerListGroupTabUi>,
    selectedGroupId: Int,
    onGroupSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (groups.isEmpty()) return

    val selectedIndex = groups.indexOfFirst { group -> group.id == selectedGroupId }
        .coerceAtLeast(0)
    TabRowWithContour(
        tabs = groups.map { group -> "${group.name} (${group.serverCount})" },
        selectedTabIndex = selectedIndex,
        onTabSelected = { index -> onGroupSelected(groups[index].id) },
        colors = TabRowDefaults.tabRowColors(
            selectedBackgroundColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.16f),
            selectedContentColor = MiuixTheme.colorScheme.primary,
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        maxWidth = 132.dp,
    )
}

@Composable
internal fun ProxyServerListAddMenu(
    onAction: (ProxyServerListAddAction) -> Unit,
) {
    IconDropdownMenu(
        imageVector = MiuixIcons.Add,
        contentDescription = stringResource(R.string.proxy_server_list_add),
        entries = proxyServerListAddMenuEntries(),
        onAction = onAction,
    )
}

@Composable
internal fun ProxyServerListToolsMenu(
    onAction: (ProxyServerListToolAction) -> Unit,
) {
    IconDropdownMenu(
        imageVector = MiuixIcons.More,
        contentDescription = stringResource(R.string.proxy_server_list_more),
        entries = proxyServerListToolMenuEntries(),
        onAction = onAction,
    )
}

@Composable
internal fun ProxyServerListSearchBar(
    searchValue: String,
    onSearchValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SearchBar(
        modifier = modifier,
        inputField = {
            InputField(
                query = searchValue,
                onQueryChange = onSearchValueChange,
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                label = stringResource(R.string.proxy_server_list_search_label),
            )
        },
        expanded = false,
        onExpandedChange = {},
    ) {}
}

@Composable
internal fun ProxyServerListItemCard(
    latency: String,
    displayText: ProxyServerListItemDisplayText,
    selected: Boolean,
    onSelect: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    groupName: String? = null,
    isDragging: Boolean = false,
    dragModifier: Modifier = Modifier,
) {
    val latencyText = latency.trim()
    val animatedScale by animateFloatAsState(
        targetValue = if (isDragging) 1.025f else 1f,
        animationSpec = folmeSpring(damping = 0.9f, response = 0.38f),
        label = "proxyServerDragScale",
    )
    val animatedShadowAlpha by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = folmeSpring(damping = 0.9f, response = 0.38f),
        label = "proxyServerDragShadowAlpha",
    )
    val shadowColor = MiuixTheme.colorScheme.primary

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .draggedCardShadow(
                alpha = animatedShadowAlpha,
                color = shadowColor,
            )
            .then(dragModifier),
        colors = CardDefaults.defaultColors(
            color = if (selected) {
                MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)
            } else {
                MiuixTheme.colorScheme.surface
            },
        ),
        insideMargin = PaddingValues(16.dp),
        onClick = onSelect,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayText.title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = displayText.summary,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (groupName != null) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = groupName,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProtocolChip(
                    text = displayText.protocol,
                    selected = selected,
                )
                if (latencyText.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = latencyText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = proxyServerLatencyColor(latencyText),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onShare) {
                    Icon(
                        imageVector = MiuixIcons.Copy,
                        contentDescription = stringResource(R.string.common_share),
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = MiuixIcons.Edit,
                        contentDescription = stringResource(R.string.common_edit),
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = MiuixIcons.Delete,
                        contentDescription = stringResource(R.string.common_delete),
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ProxyServerListFloatingToolbar(
    running: Boolean,
    serviceOperationInProgress: Boolean,
    bottomPadding: Dp,
    onToggleRunning: () -> Unit,
    onRealConnectionTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(
            end = 20.dp,
            bottom = bottomPadding + ProxyServerListFloatingToolbarBottomSpacing,
        ),
    ) {
        FloatingToolbar(
            color = MiuixTheme.colorScheme.primary,
            cornerRadius = 32.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = ProxyServerListFloatingToolbarVerticalPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedVisibility(
                    visible = running,
                    enter = slideInHorizontally(initialOffsetX = { width -> width }) +
                        expandHorizontally(expandFrom = Alignment.End),
                    exit = slideOutHorizontally(targetOffsetX = { width -> width }) +
                        shrinkHorizontally(shrinkTowards = Alignment.End),
                ) {
                    IconButton(
                        modifier = Modifier.size(ProxyServerListFloatingToolbarButtonSize),
                        onClick = onRealConnectionTest,
                    ) {
                        Icon(
                            modifier = Modifier.size(26.dp),
                            imageVector = MiuixIcons.Stopwatch,
                            contentDescription = stringResource(R.string.proxy_server_list_real_connection_test),
                            tint = MiuixTheme.colorScheme.onPrimary,
                        )
                    }
                }
                IconButton(
                    modifier = Modifier.size(ProxyServerListFloatingToolbarButtonSize),
                    onClick = {
                        if (!serviceOperationInProgress) {
                            onToggleRunning()
                        }
                    },
                ) {
                    Icon(
                        modifier = Modifier.size(26.dp),
                        imageVector = if (running) MiuixIcons.Pause else MiuixIcons.Play,
                        contentDescription = if (running) {
                            stringResource(R.string.proxy_server_list_stop_proxy)
                        } else {
                            stringResource(R.string.proxy_server_list_start_proxy)
                        },
                        tint = MiuixTheme.colorScheme.onPrimary.copy(
                            alpha = if (serviceOperationInProgress) 0.45f else 1f,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ProxyServerListEmptyState(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun proxyServerLatencyColor(text: String): Color {
    val latency = proxyServerLatencyNumberRegex.find(text)?.value?.toIntOrNull()
    val darkTheme = isInDarkTheme()
    return when {
        latency == null -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        latency < 100 -> if (darkTheme) Color(0xFF6BD58A) else Color(0xFF128A3C)
        latency < 200 -> if (darkTheme) Color(0xFFFFC857) else Color(0xFFD18A00)
        latency < 400 -> if (darkTheme) Color(0xFFFF9B63) else Color(0xFFE06400)
        else -> MiuixTheme.colorScheme.error
    }
}

@Composable
private fun ProtocolChip(
    text: String,
    selected: Boolean = false,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                },
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) {
                MiuixTheme.colorScheme.onPrimary
            } else {
                MiuixTheme.colorScheme.primary
            },
        )
    }
}

@Composable
private fun proxyServerListAddMenuEntries() = listOf(
    ProxyServerListMenuEntry(stringResource(R.string.proxy_server_list_scan_qr_code), ProxyServerListAddAction.ScanQrCode),
    ProxyServerListMenuEntry(stringResource(R.string.proxy_server_list_import_clipboard), ProxyServerListAddAction.Clipboard),
    ProxyServerListMenuEntry(stringResource(R.string.proxy_server_list_import_file), ProxyServerListAddAction.File),
    ProxyServerListMenuEntry(stringResource(R.string.proxy_server_list_add_strategy_group), ProxyServerListAddAction.StrategyGroup),
    ProxyServerListMenuEntry(stringResource(R.string.proxy_server_list_add_chain_proxy), ProxyServerListAddAction.ChainProxy),
    ProxyServerListMenuEntry(stringResource(R.string.proxy_server_list_add_custom), ProxyServerListAddAction.Custom),
    ProxyServerListMenuEntry(stringResource(R.string.proxy_server_list_add_http), ProxyServerListAddAction.HTTP),
    ProxyServerListMenuEntry(stringResource(R.string.proxy_server_list_add_vmess), ProxyServerListAddAction.VMess),
    ProxyServerListMenuEntry(stringResource(R.string.proxy_server_list_add_vless), ProxyServerListAddAction.VLESS),
    ProxyServerListMenuEntry(stringResource(R.string.proxy_server_list_add_trojan), ProxyServerListAddAction.Trojan),
    ProxyServerListMenuEntry(stringResource(R.string.proxy_server_list_add_shadowsocks), ProxyServerListAddAction.Shadowsocks),
    ProxyServerListMenuEntry(stringResource(R.string.proxy_server_list_add_socks), ProxyServerListAddAction.Socks),
    ProxyServerListMenuEntry(stringResource(R.string.proxy_server_list_add_hysteria2), ProxyServerListAddAction.Hysteria2),
    ProxyServerListMenuEntry(stringResource(R.string.proxy_server_list_add_wireguard), ProxyServerListAddAction.Wireguard),
).map { entry ->
    IconDropdownMenuEntry(
        key = entry.action,
        title = entry.title,
        action = entry.action,
    )
}

@Composable
private fun proxyServerListToolMenuEntries() = listOf(
    ProxyServerListToolMenuEntry(stringResource(R.string.proxy_server_list_restart_service), ProxyServerListToolAction.RestartService),
    ProxyServerListToolMenuEntry(stringResource(R.string.proxy_server_list_latency_test), ProxyServerListToolAction.TestLatency),
    ProxyServerListToolMenuEntry(stringResource(R.string.proxy_server_list_real_connection_test), ProxyServerListToolAction.TestRealConnection),
    ProxyServerListToolMenuEntry(stringResource(R.string.proxy_server_list_sort_by_test_result), ProxyServerListToolAction.SortByTestResult),
    ProxyServerListToolMenuEntry(stringResource(R.string.proxy_server_list_update_subscriptions), ProxyServerListToolAction.UpdateSubscriptions),
    ProxyServerListToolMenuEntry(stringResource(R.string.proxy_server_list_copy_all_urls), ProxyServerListToolAction.CopyAllUrls),
    ProxyServerListToolMenuEntry(stringResource(R.string.proxy_server_list_delete_duplicates), ProxyServerListToolAction.DeleteDuplicateServers),
).map { entry ->
    IconDropdownMenuEntry(
        key = entry.action,
        title = entry.title,
        action = entry.action,
    )
}
