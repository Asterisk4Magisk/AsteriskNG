// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import app.CustomResourceFileState
import app.CustomResourceFileStatus
import app.ResourceFileStatus
import app.ResourceFileUpdateSource
import features.subscription.DefaultSubscriptionUserAgent
import features.subscription.SubscriptionUserAgentSelection
import features.subscription.SubscriptionUserAgentSelections
import features.subscription.resolveUserAgent
import features.subscription.subscriptionUserAgentSelectionFor
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Replace
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import ui.text.formatTemplate
import java.text.DateFormat
import java.util.Date

@Composable
internal fun settingsResourceFileSourceOptions() = listOf(
    stringResource(R.string.settings_resource_files_source_loyalsoldier_github),
    stringResource(R.string.settings_resource_files_source_v2fly_github),
    stringResource(R.string.settings_resource_files_source_chocolate4u_github),
    stringResource(R.string.settings_resource_files_source_runetfreedom_github),
    stringResource(R.string.settings_resource_files_source_custom),
)

@Composable
internal fun ResourceFileSourceCard(
    sourceOptions: List<String>,
    selectedSource: Int,
    selectedUpdateSource: ResourceFileUpdateSource,
    customGeoIpUrl: String,
    customGeoSiteUrl: String,
    customGeoIpOnlyCnPrivateUrl: String,
    customDirectCidrIpv4Url: String,
    customDirectCidrIpv6Url: String,
    userAgent: String,
    updating: Boolean,
    onSourceChange: (Int) -> Unit,
    onCustomSourceChange: (
        geoIpUrl: String,
        geoSiteUrl: String,
        geoIpOnlyCnPrivateUrl: String,
        directCidrIpv4Url: String,
        directCidrIpv6Url: String,
    ) -> Unit,
    onUserAgentChange: (String) -> Unit,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    actionsEnabled: Boolean = true,
) {
    var showCustomSourceDialog by remember { mutableStateOf(false) }
    var showCustomUserAgentDialog by remember { mutableStateOf(false) }
    val geoIpUrlDraftState = rememberTextFieldState(initialText = customGeoIpUrl)
    val geoSiteUrlDraftState = rememberTextFieldState(initialText = customGeoSiteUrl)
    val geoIpOnlyCnPrivateUrlDraftState = rememberTextFieldState(initialText = customGeoIpOnlyCnPrivateUrl)
    val directCidrIpv4UrlDraftState = rememberTextFieldState(initialText = customDirectCidrIpv4Url)
    val directCidrIpv6UrlDraftState = rememberTextFieldState(initialText = customDirectCidrIpv6Url)
    val customUserAgentDraftState = rememberTextFieldState()
    val selectedIndex = selectedSource.takeIf { it in sourceOptions.indices } ?: 0
    val sourceItems = sourceOptions.map { option ->
        DropdownItem(
            text = option,
        )
    }
    val userAgentItems = SubscriptionUserAgentSelections.map { selection ->
        DropdownItem(
            text = when (selection) {
                SubscriptionUserAgentSelection.V2rayNg -> "v2rayNG"
                SubscriptionUserAgentSelection.ClashMeta -> "Clash Meta"
                SubscriptionUserAgentSelection.FlClashX -> "FlClash X"
                SubscriptionUserAgentSelection.Custom -> stringResource(R.string.subscription_user_agent_custom)
            },
        )
    }
    val currentUserAgentSelection = subscriptionUserAgentSelectionFor(userAgent)
    val selectedUserAgentIndex = SubscriptionUserAgentSelections.indexOf(currentUserAgentSelection)
        .takeIf { it >= 0 } ?: 0

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        OverlaySpinnerPreference(
            title = stringResource(R.string.settings_resource_files_source),
            items = sourceItems,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = { index ->
                if (index == ResourceFileSourceCustom) {
                    geoIpUrlDraftState.setTextAndPlaceCursorAtEnd(
                        customGeoIpUrl.ifBlank { selectedUpdateSource.geoIpUrl },
                    )
                    geoSiteUrlDraftState.setTextAndPlaceCursorAtEnd(
                        customGeoSiteUrl.ifBlank { selectedUpdateSource.geoSiteUrl },
                    )
                    geoIpOnlyCnPrivateUrlDraftState.setTextAndPlaceCursorAtEnd(
                        customGeoIpOnlyCnPrivateUrl.ifBlank { selectedUpdateSource.geoIpOnlyCnPrivateUrl },
                    )
                    directCidrIpv4UrlDraftState.setTextAndPlaceCursorAtEnd(
                        customDirectCidrIpv4Url.ifBlank { selectedUpdateSource.directCidrIpv4Url },
                    )
                    directCidrIpv6UrlDraftState.setTextAndPlaceCursorAtEnd(
                        customDirectCidrIpv6Url.ifBlank { selectedUpdateSource.directCidrIpv6Url },
                    )
                    showCustomSourceDialog = true
                } else {
                    onSourceChange(index)
                }
            },
        )
        CustomResourceFileSourceDialog(
            show = showCustomSourceDialog,
            geoIpUrlState = geoIpUrlDraftState,
            geoSiteUrlState = geoSiteUrlDraftState,
            geoIpOnlyCnPrivateUrlState = geoIpOnlyCnPrivateUrlDraftState,
            directCidrIpv4UrlState = directCidrIpv4UrlDraftState,
            directCidrIpv6UrlState = directCidrIpv6UrlDraftState,
            onDismissRequest = { showCustomSourceDialog = false },
            onSave = {
                onCustomSourceChange(
                    geoIpUrlDraftState.text.toString().trim().ifBlank { selectedUpdateSource.geoIpUrl },
                    geoSiteUrlDraftState.text.toString().trim().ifBlank { selectedUpdateSource.geoSiteUrl },
                    geoIpOnlyCnPrivateUrlDraftState.text.toString().trim()
                        .ifBlank { selectedUpdateSource.geoIpOnlyCnPrivateUrl },
                    directCidrIpv4UrlDraftState.text.toString().trim()
                        .ifBlank { selectedUpdateSource.directCidrIpv4Url },
                    directCidrIpv6UrlDraftState.text.toString().trim()
                        .ifBlank { selectedUpdateSource.directCidrIpv6Url },
                )
                onSourceChange(ResourceFileSourceCustom)
                showCustomSourceDialog = false
            },
        )
        OverlaySpinnerPreference(
            title = stringResource(R.string.subscription_user_agent),
            summary = userAgent,
            items = userAgentItems,
            selectedIndex = selectedUserAgentIndex,
            onSelectedIndexChange = { index ->
                val selection = SubscriptionUserAgentSelections[index]
                if (selection == SubscriptionUserAgentSelection.Custom) {
                    customUserAgentDraftState.setTextAndPlaceCursorAtEnd(
                        userAgent.ifBlank { DefaultSubscriptionUserAgent },
                    )
                    showCustomUserAgentDialog = true
                } else {
                    onUserAgentChange(selection.resolveUserAgent(customUserAgent = ""))
                }
            },
        )
        CustomResourceFileUserAgentDialog(
            show = showCustomUserAgentDialog,
            state = customUserAgentDraftState,
            onDismissRequest = { showCustomUserAgentDialog = false },
            onSave = {
                val custom = customUserAgentDraftState.text.toString().trim()
                    .ifBlank { DefaultSubscriptionUserAgent }
                onUserAgentChange(custom)
                showCustomUserAgentDialog = false
            },
        )
        ArrowPreference(
            title = stringResource(
                if (updating) R.string.common_cancel else R.string.settings_resource_files_update,
            ),
            onClick = { if (updating) onCancel() else onUpdate() },
            enabled = updating || actionsEnabled,
        )
    }
}

@Composable
private fun CustomResourceFileSourceDialog(
    show: Boolean,
    geoIpUrlState: TextFieldState,
    geoSiteUrlState: TextFieldState,
    geoIpOnlyCnPrivateUrlState: TextFieldState,
    directCidrIpv4UrlState: TextFieldState,
    directCidrIpv6UrlState: TextFieldState,
    onDismissRequest: () -> Unit,
    onSave: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = stringResource(R.string.settings_resource_files_source_custom_title),
        onDismissRequest = onDismissRequest,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                TextField(
                    state = geoIpUrlState,
                    label = ResourceFileGeoIpName,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
                TextField(
                    state = geoSiteUrlState,
                    label = ResourceFileGeoSiteName,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
                TextField(
                    state = geoIpOnlyCnPrivateUrlState,
                    label = ResourceFileGeoIpOnlyCnPrivateName,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
                TextField(
                    state = directCidrIpv4UrlState,
                    label = ResourceFileDirectCidrIpv4Name,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
                TextField(
                    state = directCidrIpv6UrlState,
                    label = ResourceFileDirectCidrIpv6Name,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
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
            }
        },
    )
}

@Composable
internal fun ResourceFileCard(
    fileName: String,
    status: ResourceFileStatus,
    updateState: ResourceFileUpdateDisplayState = ResourceFileUpdateDisplayState.Idle,
    onReplace: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
    onUpdate: (() -> Unit)? = null,
    description: String? = null,
    actionsEnabled: Boolean = true,
) {
    val cardActionsEnabled = actionsEnabled && updateState == ResourceFileUpdateDisplayState.Idle
    ResourceFileCardSurface(
        fileName = fileName,
        status = status,
        description = description,
        modifier = modifier,
    ) {
        if (onUpdate != null) {
            ResourceFileUpdateAction(
                updateState = updateState,
                enabled = actionsEnabled,
                onClick = onUpdate,
            )
        }
        IconButton(
            enabled = cardActionsEnabled,
            onClick = onReplace,
        ) {
            Icon(
                imageVector = MiuixIcons.Replace,
                contentDescription = stringResource(R.string.common_replace),
                tint = if (!cardActionsEnabled) {
                    MiuixTheme.colorScheme.disabledOnSecondaryVariant
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
            )
        }
        IconButton(
            enabled = cardActionsEnabled,
            onClick = onRestore,
        ) {
            Icon(
                imageVector = MiuixIcons.Reset,
                contentDescription = stringResource(R.string.common_restore),
                tint = if (!cardActionsEnabled) {
                    MiuixTheme.colorScheme.disabledOnSecondaryVariant
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun ResourceFileCardSurface(
    fileName: String,
    status: ResourceFileStatus,
    modifier: Modifier = Modifier,
    description: String? = null,
    actions: @Composable () -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = fileName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!description.isNullOrBlank()) {
                        Text(
                            text = description,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (status.exists) {
                        Text(
                            text = status.sizeText(),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = status.updatedAtText(),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                ResourceFileStatusChip(
                    text = if (status.exists) {
                        stringResource(R.string.settings_resource_files_ready)
                    } else {
                        stringResource(R.string.settings_resource_files_missing)
                    },
                    ready = status.exists,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        }
    }
}

@Composable
internal fun CustomResourceFileEditorDialog(
    show: Boolean,
    nameState: TextFieldState,
    urlState: TextFieldState,
    onDismissRequest: () -> Unit,
    onSave: (name: String, url: String) -> Boolean,
) {
    WindowDialog(
        show = show,
        title = stringResource(R.string.settings_resource_files_custom_file),
        onDismissRequest = onDismissRequest,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                TextField(
                    state = nameState,
                    label = stringResource(R.string.settings_resource_files_custom_name),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
                TextField(
                    state = urlState,
                    label = stringResource(R.string.settings_resource_files_custom_url),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
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
                        onClick = {
                            val name = nameState.text.toString()
                            val url = urlState.text.toString()
                            if (onSave(name, url)) {
                                onDismissRequest()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    )
}

@Composable
internal fun CustomResourceFileCard(
    fileStatus: CustomResourceFileStatus,
    updateState: ResourceFileUpdateDisplayState = ResourceFileUpdateDisplayState.Idle,
    onUpdate: (CustomResourceFileState) -> Unit,
    onReplace: (CustomResourceFileState) -> Unit,
    onEdit: (CustomResourceFileState) -> Unit,
    onDelete: (CustomResourceFileState) -> Unit,
    modifier: Modifier = Modifier,
    actionsEnabled: Boolean = true,
) {
    val cardActionsEnabled = actionsEnabled && updateState == ResourceFileUpdateDisplayState.Idle
    ResourceFileCardSurface(
        fileName = fileStatus.file.name,
        status = fileStatus.status,
        modifier = modifier,
    ) {
        if (fileStatus.file.url.isNotBlank()) {
            ResourceFileUpdateAction(
                updateState = updateState,
                enabled = actionsEnabled,
                onClick = { onUpdate(fileStatus.file) },
            )
        }
        IconButton(
            enabled = cardActionsEnabled,
            onClick = { onReplace(fileStatus.file) },
        ) {
            Icon(
                imageVector = MiuixIcons.Replace,
                contentDescription = stringResource(R.string.common_replace),
                tint = if (!cardActionsEnabled) {
                    MiuixTheme.colorScheme.disabledOnSecondaryVariant
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
            )
        }
        IconButton(
            enabled = cardActionsEnabled,
            onClick = { onEdit(fileStatus.file) },
        ) {
            Icon(
                imageVector = MiuixIcons.Edit,
                contentDescription = stringResource(R.string.common_edit),
                tint = if (!cardActionsEnabled) {
                    MiuixTheme.colorScheme.disabledOnSecondaryVariant
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
            )
        }
        IconButton(
            enabled = cardActionsEnabled,
            onClick = { onDelete(fileStatus.file) },
        ) {
            Icon(
                imageVector = MiuixIcons.Delete,
                contentDescription = stringResource(R.string.common_delete),
                tint = if (!cardActionsEnabled) {
                    MiuixTheme.colorScheme.disabledOnSecondaryVariant
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun ResourceFileUpdateAction(
    updateState: ResourceFileUpdateDisplayState,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    when (updateState) {
        ResourceFileUpdateDisplayState.Idle -> IconButton(
            enabled = enabled,
            onClick = onClick,
        ) {
            Icon(
                imageVector = MiuixIcons.Refresh,
                contentDescription = stringResource(R.string.common_update),
                tint = if (enabled) {
                    MiuixTheme.colorScheme.onSurface
                } else {
                    MiuixTheme.colorScheme.disabledOnSecondaryVariant
                },
            )
        }

        ResourceFileUpdateDisplayState.Queued -> IconButton(
            enabled = false,
            onClick = {},
        ) {
            Icon(
                imageVector = MiuixIcons.Refresh,
                contentDescription = stringResource(R.string.settings_resource_file_update_queued),
                tint = MiuixTheme.colorScheme.primary,
            )
        }

        ResourceFileUpdateDisplayState.Running -> IconButton(
            enabled = false,
            onClick = {},
        ) {
            val updatingDescription = stringResource(R.string.settings_resource_file_updating)
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .semantics { contentDescription = updatingDescription },
                contentAlignment = Alignment.Center,
            ) {
                InfiniteProgressIndicator(
                    color = MiuixTheme.colorScheme.primary,
                    size = 20.dp,
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

@Composable
private fun ResourceFileStatusChip(
    text: String,
    ready: Boolean,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (ready) {
                    MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MiuixTheme.colorScheme.error.copy(alpha = 0.12f)
                },
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (ready) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ResourceFileStatus.updatedAtText(): String {
    return stringResource(R.string.settings_resource_files_updated_at)
        .formatTemplate("time" to updatedAtMillis.toReadableDateTime())
}

@Composable
private fun ResourceFileStatus.sizeText(): String {
    return stringResource(R.string.settings_resource_files_size).formatTemplate("size" to sizeBytes.toReadableSize())
}

private fun Long.toReadableSize(): String {
    if (this < 1024) return "$this B"
    val kib = this / 1024.0
    if (kib < 1024) return "${kib.formatOneDecimal()} KiB"
    val mib = kib / 1024.0
    return "${mib.formatOneDecimal()} MiB"
}

private fun Double.formatOneDecimal(): String {
    val rounded = kotlin.math.round(this * 10.0) / 10.0
    val text = rounded.toString()
    return text.removeSuffix(".0")
}

private fun Long.toReadableDateTime(): String {
    if (this <= 0L) return "-"
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(this))
}

@Composable
internal fun CustomResourceFileUserAgentDialog(
    show: Boolean,
    state: TextFieldState,
    onDismissRequest: () -> Unit,
    onSave: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = stringResource(R.string.subscription_custom_user_agent),
        onDismissRequest = onDismissRequest,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                TextField(
                    state = state,
                    label = stringResource(R.string.subscription_custom_user_agent),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
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
            }
        },
    )
}
