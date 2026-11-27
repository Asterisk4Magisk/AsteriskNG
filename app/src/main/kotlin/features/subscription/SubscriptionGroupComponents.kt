// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.subscription

import androidx.compose.ui.platform.LocalContext
import data.AppSettingsPreferences
import androidx.compose.runtime.getValue
import app.SubscriptionGroupState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.byValue
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.input.then
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.R
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import features.proxy.server.display.displayName
import ui.components.AnimatedFoldDialog
import ui.components.IconDropdownMenu
import ui.components.IconDropdownMenuEntry
import ui.components.WarningConfirmDialog
import ui.components.animateDraggedCardLift
import ui.components.draggedCardLift
import ui.layout.EditorEntryTransition
import ui.layout.LocalAdaptiveWindow
import ui.text.formatTemplate

@Composable
internal fun SubscriptionGroupEditorDialog(
    show: Boolean,
    editorSession: Int,
    group: SubscriptionGroupState?,
    nextGroupId: Int,
    onDismissRequest: () -> Unit,
    onSave: (SubscriptionGroupState, isNew: Boolean) -> Unit,
    onInvalidUrl: () -> Unit,
    onDirtyChanged: (Boolean) -> Unit,
    inline: Boolean,
    modifier: Modifier = Modifier,
) {
    val isEditing = group != null
    val builtIn = group?.builtIn == true
    val defaultGroupName = stringResource(R.string.subscription_default_group)
    val unnamedGroupName = stringResource(R.string.subscription_unnamed_group)

    var name by remember(editorSession, group?.id, defaultGroupName, builtIn) {
        mutableStateOf(
            when {
                builtIn -> group.displayName(defaultGroupName)
                else -> group?.name.orEmpty()
            },
        )
    }
    var url by remember(editorSession, group?.id) { mutableStateOf(group?.url ?: "") }
    val context = LocalContext.current.applicationContext
    val defaultRequestHeaders = remember(context) {
        defaultSubscriptionRequestHeaders(AppSettingsPreferences(context).getOrCreateSubscriptionHwid())
    }
    val initialRequestHeaders = remember(editorSession, group?.id, defaultRequestHeaders) {
        formatSubscriptionRequestHeaders(group?.requestHeaders ?: defaultRequestHeaders)
    }
    var requestHeadersInput by remember(editorSession, group?.id) { mutableStateOf(initialRequestHeaders) }
    val requestHeaders = remember(requestHeadersInput) { parseSubscriptionRequestHeaders(requestHeadersInput) }
    var ageSecretKey by remember(editorSession, group?.id) { mutableStateOf(group?.ageSecretKey ?: "") }
    val initialUserAgent = group?.userAgent ?: DefaultSubscriptionUserAgent
    var userAgentSelection by remember(editorSession, group?.id, initialUserAgent) {
        mutableStateOf(subscriptionUserAgentSelectionFor(initialUserAgent))
    }
    var customUserAgent by remember(editorSession, group?.id, initialUserAgent) {
        mutableStateOf(
            initialUserAgent.takeIf {
                subscriptionUserAgentSelectionFor(it) == SubscriptionUserAgentSelection.Custom
            }.orEmpty(),
        )
    }
    var interval by remember(editorSession, group?.id) {
        mutableStateOf(sanitizeSubscriptionIntervalInput(group?.updateInterval.orEmpty()))
    }
    var updateViaProxy by remember(editorSession, group?.id) {
        mutableStateOf(group?.updateViaProxy ?: false)
    }
    var showCustomUserAgentDialog by remember(editorSession, group?.id) { mutableStateOf(false) }
    var showHttpSubscriptionWarning by remember(editorSession, group?.id) { mutableStateOf(false) }
    val customUserAgentDraftState = rememberTextFieldState(initialText = customUserAgent)
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
    val dirty = (!builtIn && name != group?.name.orEmpty()) || url != group?.url.orEmpty() ||
        requestHeadersInput != initialRequestHeaders ||
        ageSecretKey != group?.ageSecretKey.orEmpty() ||
        resolvedUserAgent != initialUserAgent ||
        interval != sanitizeSubscriptionIntervalInput(group?.updateInterval.orEmpty()) ||
        updateViaProxy != (group?.updateViaProxy ?: false)
    SideEffect { onDirtyChanged(show && dirty) }

    fun saveGroup(allowPlainHttp: Boolean = false) {
        if (!isValidSubscriptionIntervalInput(interval)) return
        val savedUrl = url.trim()
        if (savedUrl.isNotBlank() && requestHeaders == null) return
        if (savedUrl.isNotBlank() && !savedUrl.isValidManualSubscriptionUrl()) {
            onInvalidUrl()
            return
        }
        if (!allowPlainHttp && savedUrl.isPlainHttpSubscriptionUrl()) {
            showHttpSubscriptionWarning = true
            return
        }

        showHttpSubscriptionWarning = false
        val storedRequestHeaders = requestHeaders?.withoutImplicitSubscriptionHeaders(
            group?.requestHeaders, defaultRequestHeaders,
        )
        val savedUserAgent = userAgentSelection.resolveUserAgent(customUserAgent)
        // A blank name on a URL group is auto-assigned on its first sync.
        val savedName = name.trim().ifBlank {
            if (savedUrl.isNotBlank()) "" else unnamedGroupName
        }
        val savedGroup = group?.copy(
            name = if (group.builtIn) group.name else savedName,
            url = savedUrl,
            requestHeaders = storedRequestHeaders,
            ageSecretKey = ageSecretKey.trim(),
            userAgent = savedUserAgent,
            updateInterval = interval.trim().takeIf { savedUrl.isNotBlank() }.orEmpty(),
            updateViaProxy = updateViaProxy && savedUrl.isNotBlank(),
        ) ?: SubscriptionGroupState(
                id = nextGroupId,
                name = savedName,
                url = savedUrl,
                requestHeaders = storedRequestHeaders,
                ageSecretKey = ageSecretKey.trim(),
                userAgent = savedUserAgent,
                updateInterval = interval.trim().takeIf { savedUrl.isNotBlank() }.orEmpty(),
                updateViaProxy = updateViaProxy && savedUrl.isNotBlank(),
                enabled = true,
            )
        onSave(savedGroup, group == null)
        onDismissRequest()
    }

    SubscriptionEditorContainer(
        show = show,
        title = if (isEditing) stringResource(R.string.subscription_edit) else stringResource(R.string.subscription_add),
        onDismissRequest = onDismissRequest,
        inline = inline,
        selectedId = group?.id,
        modifier = modifier,
    ) {
        key(editorSession, group?.id, builtIn) {
            Column(modifier = Modifier.fillMaxWidth()) {
                val scrollState = rememberScrollState()
                var viewportHeight by remember { mutableIntStateOf(0) }
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .onSizeChanged { viewportHeight = it.height }
                        .clipToBounds()
                        .verticalScroll(scrollState)
                        .padding(bottom = 16.dp),
                ) {
                    TextField(
                        state = rememberTextFieldState(initialText = name),
                        inputTransformation = InputTransformation {
                            name = asCharSequence().toString()
                        },
                        // miuix TextField has no placeholder slot; show the hint as the label while empty.
                        label = if (name.isBlank() && !builtIn) {
                            stringResource(R.string.subscription_group_name_placeholder)
                        } else {
                            stringResource(R.string.subscription_group_name)
                        },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        enabled = !builtIn,
                        modifier = Modifier.padding(bottom = 12.dp).keepSubscriptionFieldVisible(viewportHeight),
                    )
                    TextField(
                        state = rememberTextFieldState(initialText = url),
                        inputTransformation = InputTransformation {
                            url = asCharSequence().toString()
                        },
                        label = stringResource(R.string.subscription_url),
                        lineLimits = TextFieldLineLimits.SingleLine,
                        modifier = Modifier.padding(bottom = 12.dp).keepSubscriptionFieldVisible(viewportHeight),
                    )
                    AnimatedVisibility(
                        visible = url.isNotBlank(),
                        enter = fadeIn() + expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Column {
                            TextField(
                                state = rememberTextFieldState(initialText = ageSecretKey),
                                inputTransformation = InputTransformation {
                                    ageSecretKey = asCharSequence().toString()
                                },
                                label = stringResource(R.string.subscription_age_secret_key),
                                lineLimits = TextFieldLineLimits.SingleLine,
                                modifier = Modifier.padding(bottom = 12.dp).keepSubscriptionFieldVisible(viewportHeight),
                            )
                            TextField(
                                state = rememberTextFieldState(initialText = requestHeadersInput),
                                inputTransformation = InputTransformation {
                                    requestHeadersInput = asCharSequence().toString()
                                },
                                label = stringResource(R.string.subscription_request_headers),
                                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),
                                modifier = Modifier.padding(bottom = 12.dp).keepSubscriptionFieldVisible(viewportHeight),
                            )
                            if (requestHeaders == null) {
                                Text(
                                    text = stringResource(R.string.subscription_request_headers_invalid),
                                    color = MiuixTheme.colorScheme.error,
                                    modifier = Modifier.padding(bottom = 12.dp),
                                )
                            }
                            WindowSpinnerPreference(
                                title = stringResource(R.string.subscription_user_agent),
                                summary = resolvedUserAgent,
                                items = userAgentItems,
                                selectedIndex = selectedUserAgentIndex,
                                onSelectedIndexChange = { index ->
                                    val selection = SubscriptionUserAgentSelections[index]
                                    if (selection == SubscriptionUserAgentSelection.Custom) {
                                        customUserAgentDraftState.setTextAndPlaceCursorAtEnd(
                                            customUserAgent.ifBlank { resolvedUserAgent },
                                        )
                                        showCustomUserAgentDialog = true
                                    } else {
                                        userAgentSelection = selection
                                    }
                                },
                            )
                            CustomUserAgentDialog(
                                show = showCustomUserAgentDialog,
                                state = customUserAgentDraftState,
                                onDismissRequest = { showCustomUserAgentDialog = false },
                                onSave = {
                                    customUserAgent = customUserAgentDraftState.text.toString().trim()
                                        .ifBlank { DefaultSubscriptionUserAgent }
                                    userAgentSelection = SubscriptionUserAgentSelection.Custom
                                    showCustomUserAgentDialog = false
                                },
                            )
                            SwitchPreference(
                                title = stringResource(R.string.subscription_update_via_proxy),
                                summary = stringResource(R.string.subscription_update_via_proxy_summary),
                                checked = updateViaProxy,
                                onCheckedChange = { updateViaProxy = it },
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                            TextField(
                                state = rememberTextFieldState(initialText = interval),
                                inputTransformation = InputTransformation
                                    .byValue { _, proposed ->
                                        sanitizeSubscriptionIntervalInput(proposed.toString())
                                    }
                                    .then { interval = asCharSequence().toString() },
                                label = stringResource(R.string.subscription_auto_update_interval),
                                lineLimits = TextFieldLineLimits.SingleLine,
                                modifier = Modifier.keepSubscriptionFieldVisible(viewportHeight),
                            )
                        }
                    }
                }
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
                        onClick = { saveGroup() },
                        enabled = isValidSubscriptionIntervalInput(interval) && (url.isBlank() || requestHeaders != null),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    HttpSubscriptionWarningDialog(
        show = showHttpSubscriptionWarning,
        onDismissRequest = { showHttpSubscriptionWarning = false },
        onConfirm = { saveGroup(allowPlainHttp = true) },
    )
}

@Composable
private fun SubscriptionEditorContainer(
    show: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    inline: Boolean = false,
    selectedId: Int? = null,
    content: @Composable () -> Unit,
) {
    if (inline) {
        EditorEntryTransition(
            selectedId = selectedId,
            animateChanges = true,
            modifier = modifier,
        ) {
            SubscriptionEditorCard(title, Modifier.fillMaxWidth(), content)
        }
        return
    }

    val window = LocalAdaptiveWindow.current
    if (window.hasSeparatingFold) {
        AnimatedFoldDialog(
            show = show,
            alignment = Alignment.Center,
            onDismissRequest = onDismissRequest,
        ) {
            SubscriptionEditorCard(
                title = title,
                modifier = Modifier.imePadding().padding(horizontal = 16.dp, vertical = 12.dp)
                    .widthIn(max = 460.dp).fillMaxWidth(),
                content = content,
            )
        }
    } else {
        WindowDialog(show = show, title = title, onDismissRequest = onDismissRequest) {
            content()
        }
    }
}

@Composable
private fun Modifier.keepSubscriptionFieldVisible(viewportHeight: Int): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused, viewportHeight) {
        if (focused && viewportHeight > 0) {
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    return bringIntoViewRequester(requester).onFocusChanged { focused = it.hasFocus }
}

@Composable
private fun SubscriptionEditorCard(
    title: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(20.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.background,
            contentColor = MiuixTheme.colorScheme.onBackground,
        ),
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.title4,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        content()
    }
}

@Composable
private fun HttpSubscriptionWarningDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    WarningConfirmDialog(
        show = show,
        title = stringResource(R.string.subscription_http_warning_title),
        summary = stringResource(R.string.subscription_http_warning_message),
        dismissText = stringResource(R.string.common_cancel),
        confirmText = stringResource(R.string.subscription_http_warning_confirm),
        onDismissRequest = onDismissRequest,
        onConfirm = onConfirm,
    )
}

@Composable
private fun CustomUserAgentDialog(
    show: Boolean,
    state: TextFieldState,
    onDismissRequest: () -> Unit,
    onSave: () -> Unit,
) {
    SubscriptionEditorContainer(
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

private fun SubscriptionUserAgentSelection.labelResId(): Int = when (this) {
    SubscriptionUserAgentSelection.V2rayNg -> R.string.subscription_user_agent_v2rayng
    SubscriptionUserAgentSelection.ClashMeta -> R.string.subscription_user_agent_clash_meta
    SubscriptionUserAgentSelection.Custom -> R.string.subscription_user_agent_custom
}

@Composable
internal fun SubscriptionGroupCard(
    group: SubscriptionGroupState,
    isDragging: Boolean,
    onToggle: (Boolean) -> Unit,
    onUpdate: (() -> Unit)?,
    shareActions: List<SubscriptionGroupShareAction>,
    onShareAction: (SubscriptionGroupShareAction) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    dragModifier: Modifier = Modifier,
    editing: Boolean = false,
    editEnabled: Boolean = true,
    deleteEnabled: Boolean = true,
    quickSelection: Boolean = false,
) {
    val defaultGroupName = stringResource(R.string.subscription_default_group)
    val dragLift = animateDraggedCardLift(isDragging, label = "subscriptionGroupDrag")

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .draggedCardLift(dragLift)
            .then(dragModifier)
            .then(
                if (quickSelection) {
                    Modifier.selectable(
                        selected = editing,
                        enabled = editEnabled,
                        role = Role.Button,
                        onClick = onEdit,
                    )
                } else Modifier,
            ),
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
                    color = if (editing) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
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
            if (onUpdate != null) {
                IconButton(onClick = onUpdate) {
                    Icon(
                        imageVector = MiuixIcons.Refresh,
                        contentDescription = stringResource(R.string.subscription_update_group),
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
            if (shareActions.isNotEmpty()) {
                IconDropdownMenu(
                    imageVector = MiuixIcons.Copy,
                    contentDescription = stringResource(R.string.subscription_share_group),
                    entries = subscriptionGroupShareMenuEntries(shareActions),
                    onAction = onShareAction,
                )
            }
            IconButton(onClick = onEdit, enabled = editEnabled) {
                Icon(
                    imageVector = MiuixIcons.Edit,
                    contentDescription = stringResource(R.string.subscription_edit_group),
                    tint = if (editEnabled) MiuixTheme.colorScheme.onSurface
                    else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                )
            }
            IconButton(
                onClick = onDelete,
                enabled = !group.builtIn && deleteEnabled,
            ) {
                Icon(
                    imageVector = MiuixIcons.Delete,
                    contentDescription = stringResource(R.string.subscription_delete_group),
                    tint = if (group.builtIn || !deleteEnabled) {
                        MiuixTheme.colorScheme.disabledOnSecondaryVariant
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun subscriptionGroupShareMenuEntries(
    actions: List<SubscriptionGroupShareAction>,
): List<IconDropdownMenuEntry<SubscriptionGroupShareAction>> {
    return actions.map { action ->
        IconDropdownMenuEntry(
            key = action,
            title = when (action) {
                SubscriptionGroupShareAction.SubscriptionQrCode -> stringResource(R.string.subscription_share_qr_code)
                SubscriptionGroupShareAction.SubscriptionUrl -> stringResource(R.string.subscription_share_url)
                SubscriptionGroupShareAction.ServerUrls -> stringResource(R.string.proxy_server_list_copy_all_urls)
                SubscriptionGroupShareAction.ServerUrlsFile -> stringResource(R.string.subscription_share_export_file)
            },
            action = action,
        )
    }
}
