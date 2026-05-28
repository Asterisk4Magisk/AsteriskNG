package features.subscription

import app.SubscriptionGroupState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.R
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import features.proxy.server.display.displayName
import ui.text.formatTemplate

@Composable
internal fun SubscriptionGroupForm(
    name: String,
    url: String,
    userAgentSelection: SubscriptionUserAgentSelection,
    customUserAgent: String,
    interval: String,
    updateViaProxy: Boolean,
    builtIn: Boolean,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onUserAgentSelectionChange: (SubscriptionUserAgentSelection) -> Unit,
    onCustomUserAgentChange: (String) -> Unit,
    onIntervalChange: (String) -> Unit,
    onUpdateViaProxyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCustomUserAgentDialog by remember { mutableStateOf(false) }
    var customUserAgentDraft by remember(customUserAgent) { mutableStateOf(customUserAgent) }
    val customSummary = customUserAgent.trim().ifBlank {
        stringResource(R.string.subscription_user_agent_custom_summary)
    }
    val userAgentItems = SubscriptionUserAgentSelections.map { selection ->
        DropdownItem(
            text = stringResource(selection.labelResId()),
            summary = selection.userAgentOrNull() ?: customSummary,
        )
    }
    val selectedUserAgentIndex = SubscriptionUserAgentSelections
        .indexOf(userAgentSelection)
        .coerceAtLeast(0)
    val resolvedUserAgent = userAgentSelection.resolveUserAgent(customUserAgent)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        TextField(
            value = name,
            onValueChange = { if (!builtIn) onNameChange(it) },
            label = stringResource(R.string.subscription_group_name),
            singleLine = true,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        TextField(
            value = url,
            onValueChange = onUrlChange,
            label = stringResource(R.string.subscription_url),
            singleLine = true,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        OverlaySpinnerPreference(
            title = stringResource(R.string.subscription_user_agent),
            summary = resolvedUserAgent,
            items = userAgentItems,
            selectedIndex = selectedUserAgentIndex,
            dialogButtonString = stringResource(R.string.common_complete),
            modifier = Modifier.padding(bottom = 12.dp),
            onSelectedIndexChange = { index ->
                val selection = SubscriptionUserAgentSelections[index]
                if (selection == SubscriptionUserAgentSelection.Custom) {
                    customUserAgentDraft = customUserAgent.ifBlank { resolvedUserAgent }
                    showCustomUserAgentDialog = true
                } else {
                    onUserAgentSelectionChange(selection)
                }
            },
        )
        CustomUserAgentDialog(
            show = showCustomUserAgentDialog,
            value = customUserAgentDraft,
            onValueChange = { customUserAgentDraft = it },
            onDismissRequest = { showCustomUserAgentDialog = false },
            onSave = {
                onCustomUserAgentChange(customUserAgentDraft.trim().ifBlank { DefaultSubscriptionUserAgent })
                onUserAgentSelectionChange(SubscriptionUserAgentSelection.Custom)
                showCustomUserAgentDialog = false
            },
        )
        AnimatedVisibility(
            visible = url.isNotBlank(),
            enter = fadeIn() + expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column {
                SwitchPreference(
                    title = stringResource(R.string.subscription_update_via_proxy),
                    summary = stringResource(R.string.subscription_update_via_proxy_summary),
                    checked = updateViaProxy,
                    onCheckedChange = onUpdateViaProxyChange,
                )
                TextField(
                    value = interval,
                    onValueChange = { onIntervalChange(it.filter(Char::isDigit)) },
                    label = stringResource(R.string.subscription_auto_update_interval),
                    singleLine = true,
                )
            }
        }
    }
}

@Composable
private fun CustomUserAgentDialog(
    show: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = stringResource(R.string.subscription_custom_user_agent),
        onDismissRequest = onDismissRequest,
        content = {
            TextField(
                value = value,
                onValueChange = onValueChange,
                label = stringResource(R.string.subscription_custom_user_agent),
                singleLine = true,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onDismissRequest,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.common_save),
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    )
}

private fun SubscriptionUserAgentSelection.labelResId(): Int = when (this) {
    SubscriptionUserAgentSelection.V2rayNg -> R.string.subscription_user_agent_v2rayng
    SubscriptionUserAgentSelection.ClashMeta -> R.string.subscription_user_agent_clash_meta
    SubscriptionUserAgentSelection.FlClashX -> R.string.subscription_user_agent_flclash_x
    SubscriptionUserAgentSelection.Custom -> R.string.subscription_user_agent_custom
}

@Composable
internal fun SubscriptionGroupCard(
    group: SubscriptionGroupState,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val defaultGroupName = stringResource(R.string.subscription_default_group)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.displayName(defaultGroupName),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = group.url.ifBlank {
                        stringResource(R.string.subscription_manual_group)
                    },
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (group.url.isNotBlank()) {
                    if (group.updateInterval.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.subscription_update_interval)
                                .formatTemplate("interval" to group.updateInterval),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Text(
                        text = stringResource(R.string.subscription_user_agent_value)
                            .formatTemplate("userAgent" to group.userAgent),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Switch(
                checked = group.enabled,
                enabled = !group.builtIn,
                onCheckedChange = onToggle,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = MiuixIcons.Edit,
                    contentDescription = stringResource(R.string.subscription_edit_group),
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = onDelete,
                enabled = !group.builtIn,
            ) {
                Icon(
                    imageVector = MiuixIcons.Delete,
                    contentDescription = stringResource(R.string.subscription_delete_group),
                    tint = if (group.builtIn) {
                        MiuixTheme.colorScheme.disabledOnSecondaryVariant
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}
