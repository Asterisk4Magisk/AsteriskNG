package features.proxy.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.R
import system.ANDROID_APP_ICON_SIZE_DP
import coil3.compose.AsyncImage
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.AppRecording
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme
import features.proxy.app.model.AppPackageEntry
import features.proxy.app.model.ProxyAppIconRequest
import features.proxy.app.model.ProxyAppListUserSpaceTabUi
import ui.text.formatTemplate
import features.proxy.app.model.name
import androidx.compose.runtime.getValue
import ui.components.IconDropdownMenu
import ui.components.IconDropdownMenuEntry

@Composable
internal fun ProxyAppListUserSpaceTabs(
    tabs: List<ProxyAppListUserSpaceTabUi>,
    selectedUserId: Int?,
    onSelectedUserIdChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return

    val hapticFeedback = LocalHapticFeedback.current
    val selectedIndex = tabs.indexOfFirst { tab -> tab.id == selectedUserId }
        .coerceAtLeast(0)
    TabRowWithContour(
        tabs = tabs.map { tab -> "${tab.label} (${tab.checkedCount})" },
        selectedTabIndex = selectedIndex,
        onTabSelected = { index ->
            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
            onSelectedUserIdChange(tabs[index].id)
        },
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
internal fun ProxyAppListDisplayOptionsMenu(
    showSystemApps: Boolean,
    onShowSystemAppsChange: (Boolean) -> Unit,
) {
    IconDropdownMenu(
        imageVector = MiuixIcons.More,
        contentDescription = stringResource(R.string.proxy_app_list_display_options),
        entries = listOf(
            IconDropdownMenuEntry(
                key = "show-system-apps",
                title = stringResource(R.string.proxy_app_list_show_system_apps),
                selected = showSystemApps,
                action = !showSystemApps,
            ),
        ),
        onAction = onShowSystemAppsChange,
    )
}

@Composable
internal fun ProxyAppListModeMenu(
    modes: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
) {
    IconDropdownMenu(
        imageVector = MiuixIcons.Tune,
        contentDescription = stringResource(R.string.proxy_app_list_mode),
        entries = modes.mapIndexed { index, mode ->
            IconDropdownMenuEntry(
                key = mode,
                title = mode,
                selected = selectedIndex == index,
                action = index,
            )
        },
        onAction = onSelectedIndexChange,
    )
}

@Composable
internal fun ProxyAppListItemCard(
    app: AppPackageEntry,
    checked: Boolean,
    enabled: Boolean,
    sharedUid: Boolean,
    iconSizePx: Int,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val toggle = {
        if (enabled) {
            onCheckedChange(!checked)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
        colors = CardDefaults.defaultColors(
            color = if (app.system) {
                MiuixTheme.colorScheme.primary.copy(alpha = 0.08f)
            } else {
                MiuixTheme.colorScheme.surface
            },
        ),
        insideMargin = PaddingValues(14.dp),
        onClick = toggle,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app, iconSizePx)
            Spacer(Modifier.width(12.dp))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.name,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (enabled) {
                            MiuixTheme.colorScheme.onSurface
                        } else {
                            MiuixTheme.colorScheme.disabledOnSecondaryVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.proxy_app_list_entry_summary)
                            .formatTemplate("package" to app.packageName),
                        style = MiuixTheme.textStyles.body2,
                        color = if (enabled) {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        } else {
                            MiuixTheme.colorScheme.disabledOnSecondaryVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                UidChip(
                    uid = app.uid,
                    enabled = enabled,
                    sharedUid = sharedUid,
                )
            }
            Spacer(Modifier.width(12.dp))
            Checkbox(
                state = ToggleableState(checked),
                onClick = toggle,
                enabled = enabled,
            )
        }
    }
}

@Composable
internal fun ProxyAppListSearchBar(
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
                label = stringResource(R.string.proxy_app_list_search_label),
            )
        },
        expanded = false,
        onExpandedChange = {},
    ) {}
}

@Composable
internal fun ProxyAppListEmptyState(
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
            text = stringResource(R.string.common_empty),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun UidChip(
    uid: Int?,
    enabled: Boolean,
    sharedUid: Boolean,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (!enabled) {
                    MiuixTheme.colorScheme.disabledOnSecondaryVariant.copy(alpha = 0.10f)
                } else if (sharedUid) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                },
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = if (sharedUid) {
                "SUID:${uid?.toString() ?: "..."}"
            } else {
                "UID:${uid?.toString() ?: "..."}"
            },
            fontSize = 12.sp,
            fontWeight = if (sharedUid) FontWeight.SemiBold else FontWeight.Medium,
            color = if (!enabled) {
                MiuixTheme.colorScheme.disabledOnSecondaryVariant
            } else if (sharedUid) {
                MiuixTheme.colorScheme.onPrimary
            } else {
                MiuixTheme.colorScheme.primary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AppIcon(
    app: AppPackageEntry,
    iconSizePx: Int,
) {
    val backgroundColor by animateColorAsState(
        targetValue = MiuixTheme.colorScheme.primary.copy(alpha = if (app.system) 0.10f else 0.16f),
        animationSpec = tween(180),
        label = "per-app-icon-background",
    )
    val contentColor by animateColorAsState(
        targetValue = if (app.system) {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        } else {
            MiuixTheme.colorScheme.primary
        },
        animationSpec = tween(180),
        label = "per-app-icon-content",
    )

    Box(
        modifier = Modifier
            .size(ANDROID_APP_ICON_SIZE_DP.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            modifier = Modifier.size(24.dp),
            imageVector = MiuixIcons.AppRecording,
            contentDescription = null,
            tint = contentColor,
        )
        AsyncImage(
            model = ProxyAppIconRequest(
                packageName = app.packageName,
                sizePx = iconSizePx,
            ),
            contentDescription = app.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}
