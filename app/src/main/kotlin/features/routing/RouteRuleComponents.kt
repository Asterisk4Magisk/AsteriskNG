// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.routing

import androidx.compose.foundation.layout.Arrangement
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.DefaultRouteOutboundTag
import app.R
import engine.network.isIpOrCidrAddress
import engine.network.isPortList
import engine.xray.isValidXrayExternalDomainRule
import engine.xray.isXrayExternalDomainRuleCandidate
import features.proxy.app.AppPackagePickerAction
import features.routing.model.RouteRule
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import ui.components.AdaptiveBottomSheet
import ui.components.IconDropdownMenu
import ui.components.IconDropdownMenuEntry
import ui.components.StringListEditor
import ui.components.StringListStatusText
import ui.components.animateDraggedCardLift
import ui.components.draggedCardLift
import utils.toTrimmedNonEmptyDistinctList

internal data class RouteRuleOutboundOption(
    val tag: String,
    val label: String,
)

@Composable
internal fun RoutingPolicyCard(
    domainStrategyOptions: List<String>,
    selectedDomainStrategy: Int,
    onDomainStrategyChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        OverlayDropdownPreference(
            title = stringResource(R.string.routing_domain_strategy),
            items = domainStrategyOptions,
            selectedIndex = selectedDomainStrategy,
            onSelectedIndexChange = onDomainStrategyChange,
        )
    }
}

@Composable
internal fun RouteRuleCard(
    rule: RouteRule,
    outboundLabel: String,
    onToggle: (Boolean) -> Unit,
    isDragging: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    editEnabled: Boolean = true,
    dragModifier: Modifier = Modifier,
    quickSelection: Boolean = false,
    editing: Boolean = false,
) {
    val dragLift = animateDraggedCardLift(isDragging, label = "routeRuleDrag")

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
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.remarks,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (editing) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${stringResource(R.string.routing_outbound_tag_label)}: $outboundLabel",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggle,
                )
            }
            Spacer(Modifier.height(8.dp))
            RouteRuleLine(label = stringResource(R.string.routing_domain_label), values = rule.domain)
            RouteRuleLine(label = stringResource(R.string.routing_ip_label), values = rule.ip)
            RouteRuleLine(label = stringResource(R.string.routing_process_label), values = rule.process)
            RouteRuleLine(label = stringResource(R.string.routing_port_label), value = rule.port)
            RouteRuleLine(label = stringResource(R.string.routing_protocol_label), value = rule.protocol)
            RouteRuleLine(label = stringResource(R.string.routing_network_label), value = rule.network)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onEdit, enabled = editEnabled) {
                    Icon(
                        imageVector = MiuixIcons.Edit,
                        contentDescription = stringResource(R.string.routing_edit),
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = MiuixIcons.Delete,
                        contentDescription = stringResource(R.string.routing_delete),
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
internal fun RouteRuleEditor(
    show: Boolean,
    editorSession: Int,
    initialRule: RouteRule?,
    nextRuleId: Int,
    outboundOptions: List<RouteRuleOutboundOption>,
    onDismissRequest: () -> Unit,
    onDirtyChanged: (Boolean) -> Unit,
    onSave: (RouteRule) -> Unit,
    inline: Boolean,
    modifier: Modifier = Modifier,
) {
    val newRuleName = stringResource(R.string.routing_new_rule)
    val unnamedRuleName = stringResource(R.string.routing_unnamed_rule)
    var remarks by remember(editorSession, initialRule, newRuleName) { mutableStateOf(initialRule?.remarks ?: newRuleName) }
    var domains by remember(editorSession, initialRule) { mutableStateOf(initialRule?.domain ?: listOf("geosite:category")) }
    var ips by remember(editorSession, initialRule) { mutableStateOf(initialRule?.ip ?: emptyList()) }
    var ports by remember(editorSession, initialRule) { mutableStateOf(initialRule?.port ?: "") }
    var process by remember(editorSession, initialRule) { mutableStateOf(initialRule?.process ?: emptyList()) }
    var protocol by remember(editorSession, initialRule) { mutableStateOf(initialRule?.protocol ?: "") }
    var network by remember(editorSession, initialRule) { mutableStateOf(initialRule?.network ?: "") }
    val canSave = isPortList(ports) && isRouteNetworkList(network)
    val currentOutbound = initialRule?.outboundTag?.takeIf { it.isNotBlank() }
    val effectiveOutboundOptions = remember(outboundOptions, currentOutbound) {
        if (currentOutbound != null && outboundOptions.none { it.tag == currentOutbound }) {
            outboundOptions + RouteRuleOutboundOption(tag = currentOutbound, label = currentOutbound)
        } else {
            outboundOptions
        }
    }
    var outbound by remember(editorSession, initialRule) {
        val tag = currentOutbound ?: DefaultRouteOutboundTag
        val option = effectiveOutboundOptions.firstOrNull { it.tag == tag }
        mutableStateOf(option ?: RouteRuleOutboundOption(tag = tag, label = tag))
    }
    var pendingDomains by remember(editorSession) { mutableStateOf(false) }
    var pendingIps by remember(editorSession) { mutableStateOf(false) }
    var pendingProcess by remember(editorSession) { mutableStateOf(false) }
    val dirty = remarks != (initialRule?.remarks ?: newRuleName) ||
        domains != (initialRule?.domain ?: listOf("geosite:category")) ||
        ips != initialRule?.ip.orEmpty() || process != initialRule?.process.orEmpty() ||
        ports != initialRule?.port.orEmpty() || protocol != initialRule?.protocol.orEmpty() ||
        network != initialRule?.network.orEmpty() || outbound.tag != (currentOutbound ?: DefaultRouteOutboundTag) ||
        pendingDomains || pendingIps || pendingProcess
    SideEffect { onDirtyChanged(show && dirty) }
    val saveRule = {
        if (canSave) {
            onSave(
                RouteRule(
                    id = initialRule?.id ?: nextRuleId,
                    remarks = remarks.ifBlank { unnamedRuleName },
                    outboundTag = outbound.tag.trim().ifBlank { DefaultRouteOutboundTag },
                    domain = domains.toTrimmedNonEmptyDistinctList(),
                    ip = ips.toTrimmedNonEmptyDistinctList(),
                    port = ports.trim(),
                    process = process.toTrimmedNonEmptyDistinctList(),
                    protocol = protocol.trim(),
                    network = network.trim(),
                    enabled = initialRule?.enabled ?: true,
                ),
            )
        }
    }

    if (inline && !show) return
    val title = stringResource(if (initialRule == null) R.string.routing_add_rule else R.string.routing_edit_rule)
    val cancel: @Composable () -> Unit = {
        TextButton(text = stringResource(R.string.common_cancel), onClick = onDismissRequest)
    }
    val save: @Composable () -> Unit = {
        TextButton(text = stringResource(R.string.common_save), onClick = saveRule, enabled = !inline || canSave)
    }
    val inlineFormState = remember(editorSession) { LazyListState() }
    val sheetFormState = remember(editorSession) { LazyListState() }
    val formState = if (inline) inlineFormState else sheetFormState
    val otherFormState = if (inline) sheetFormState else inlineFormState
    LaunchedEffect(show, formState) {
        if (!show) return@LaunchedEffect
        // A taller presentation can fit the whole form. Its layout must not erase the scroll
        // position needed when returning to the shorter presentation.
        snapshotFlow { formState.isScrollInProgress }.drop(1).filter { !it }.collect {
            otherFormState.requestScrollToItem(formState.firstVisibleItemIndex, formState.firstVisibleItemScrollOffset)
        }
    }
    val formContent = rememberUpdatedState<@Composable () -> Unit> {
        LazyColumn(
            state = formState,
            modifier = Modifier.fillMaxWidth().padding(bottom = if (inline) 0.dp else 24.dp),
            contentPadding = if (inline) {
                PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)
            } else PaddingValues(),
        ) {
            item {
                RouteRuleEditorContent(
                    editorKey = "${initialRule?.id ?: nextRuleId}:$editorSession",
                    remarks = remarks,
                    domains = domains,
                    ips = ips,
                    process = process,
                    ports = ports,
                    protocol = protocol,
                    network = network,
                    effectiveOutboundOptions = effectiveOutboundOptions,
                    outbound = outbound,
                    onRemarksChange = { remarks = it },
                    onDomainsChange = { domains = it },
                    onIpsChange = { ips = it },
                    onProcessChange = { process = it },
                    onPortsChange = { ports = it },
                    onProtocolChange = { protocol = it },
                    onNetworkChange = { network = it },
                    onOutboundChange = { outbound = it },
                    onPendingDomainsChange = { pendingDomains = it },
                    onPendingIpsChange = { pendingIps = it },
                    onPendingProcessChange = { pendingProcess = it },
                )
            }
        }
    }
    val form = remember(editorSession) { movableContentOf { formContent.value() } }
    if (inline) {
        BackHandler(onBack = onDismissRequest)
        val alpha = remember { Animatable(0f) }
        LaunchedEffect(editorSession) {
            alpha.snapTo(0f)
            alpha.animateTo(1f, folmeSpring(damping = 0.95f, response = 0.4f))
        }
        Column(
            modifier = modifier.fillMaxSize()
                .graphicsLayer { this.alpha = alpha.value }
                .clip(RoundedCornerShape(16.dp))
                .background(MiuixTheme.colorScheme.surface),
        ) {
            RouteRuleEditorHeader(title = title, startAction = cancel, endAction = save)
            Box(Modifier.weight(1f)) { form() }
        }
    } else {
        AdaptiveBottomSheet(
            show = show,
            title = title,
            startAction = cancel,
            endAction = save,
            onDismissRequest = onDismissRequest,
            content = form,
        )
    }
}

@Composable
private fun RouteRuleEditorHeader(
    title: String,
    startAction: @Composable () -> Unit,
    endAction: @Composable () -> Unit,
) {
    Layout(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 12.dp),
        content = {
            Box { startAction() }
            Text(
                text = title,
                fontSize = MiuixTheme.textStyles.title4.fontSize,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Box { endAction() }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val actionConstraints = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = width / 3)
        val start = measurables[0].measure(actionConstraints)
        val end = measurables[2].measure(actionConstraints)
        // Reserve the same space on both sides, regardless of the action labels' widths.
        val sideWidth = maxOf(start.width, end.width) + 8.dp.roundToPx()
        val heading = measurables[1].measure(
            actionConstraints.copy(maxWidth = (width - 2 * sideWidth).coerceAtLeast(0)),
        )
        val height = constraints.constrainHeight(maxOf(start.height, heading.height, end.height))
        layout(width, height) {
            start.placeRelative(0, (height - start.height) / 2)
            heading.placeRelative((width - heading.width) / 2, (height - heading.height) / 2)
            end.placeRelative(width - end.width, (height - end.height) / 2)
        }
    }
}

@Composable
internal fun RoutingEmptyCard(
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(18.dp),
    ) {
        Text(
            text = stringResource(R.string.routing_empty),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun RouteRuleLine(
    label: String,
    values: List<String>,
) {
    RouteRuleLine(label, values.toTrimmedNonEmptyDistinctList().joinToString(", "))
}

@Composable
private fun RouteRuleLine(
    label: String,
    value: String,
) {
    if (value.isBlank()) return
    Text(
        text = "$label: $value",
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RouteRuleEditorContent(
    editorKey: String,
    remarks: String,
    domains: List<String>,
    ips: List<String>,
    process: List<String>,
    ports: String,
    protocol: String,
    network: String,
    effectiveOutboundOptions: List<RouteRuleOutboundOption>,
    outbound: RouteRuleOutboundOption,
    onRemarksChange: (String) -> Unit,
    onDomainsChange: (List<String>) -> Unit,
    onIpsChange: (List<String>) -> Unit,
    onProcessChange: (List<String>) -> Unit,
    onPortsChange: (String) -> Unit,
    onProtocolChange: (String) -> Unit,
    onNetworkChange: (String) -> Unit,
    onOutboundChange: (RouteRuleOutboundOption) -> Unit,
    onPendingDomainsChange: (Boolean) -> Unit,
    onPendingIpsChange: (Boolean) -> Unit,
    onPendingProcessChange: (Boolean) -> Unit,
) {
    val emptyMessage = stringResource(R.string.routing_list_empty)
    val domainInvalidMessage = stringResource(R.string.routing_domain_invalid)
    val ipInvalidMessage = stringResource(R.string.routing_ip_invalid)
    val processInvalidMessage = stringResource(R.string.routing_process_invalid)
    val portInvalidMessage = stringResource(R.string.routing_port_invalid)
    val networkInvalidMessage = stringResource(R.string.routing_network_invalid)
    val portError = if (ports.isBlank() || isPortList(ports)) null else portInvalidMessage
    val networkError = if (network.isBlank() || isRouteNetworkList(network)) null else networkInvalidMessage

    key(editorKey) {
    Column {
        TextField(
            state = rememberTextFieldState(initialText = remarks),
            inputTransformation = InputTransformation {
                onRemarksChange(asCharSequence().toString())
            },
            label = stringResource(R.string.routing_rule_name),
            lineLimits = TextFieldLineLimits.SingleLine,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        )
        StringListEditor(
            editorKey = editorKey,
            title = stringResource(R.string.routing_domain_label),
            values = domains,
            onValuesChange = onDomainsChange,
            onPendingChange = onPendingDomainsChange,
            emptyText = emptyMessage,
            validateInput = { routeDomainInputError(it, domainInvalidMessage) },
            modifier = Modifier.padding(bottom = 12.dp),
        )
        StringListEditor(
            editorKey = editorKey,
            title = stringResource(R.string.routing_ip_label),
            values = ips,
            onValuesChange = onIpsChange,
            onPendingChange = onPendingIpsChange,
            emptyText = emptyMessage,
            validateInput = { routeIpInputError(it, ipInvalidMessage) },
            modifier = Modifier.padding(bottom = 12.dp),
        )
        StringListEditor(
            editorKey = editorKey,
            title = stringResource(R.string.routing_process_label),
            values = process,
            onValuesChange = onProcessChange,
            onPendingChange = onPendingProcessChange,
            emptyText = emptyMessage,
            validateInput = { routeProcessInputError(it, processInvalidMessage) },
            modifier = Modifier.padding(bottom = 12.dp),
            headerActions = {
                AppPackagePickerAction(
                    values = process,
                    onValuesChange = onProcessChange,
                )
            },
        )
        TextField(
            state = rememberTextFieldState(initialText = ports),
            inputTransformation = InputTransformation {
                onPortsChange(asCharSequence().toString())
            },
            label = stringResource(R.string.routing_port_label),
            lineLimits = TextFieldLineLimits.SingleLine,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = if (portError == null) 12.dp else 4.dp),
        )
        portError?.let {
            StringListStatusText(
                text = it,
                error = true,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        TextField(
            state = rememberTextFieldState(initialText = protocol),
            inputTransformation = InputTransformation {
                onProtocolChange(asCharSequence().toString())
            },
            label = stringResource(R.string.routing_protocol_label),
            lineLimits = TextFieldLineLimits.SingleLine,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        )
        TextField(
            state = rememberTextFieldState(initialText = network),
            inputTransformation = InputTransformation {
                onNetworkChange(asCharSequence().toString())
            },
            label = stringResource(R.string.routing_network_label),
            lineLimits = TextFieldLineLimits.SingleLine,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = if (networkError == null) 12.dp else 4.dp),
        )
        networkError?.let {
            StringListStatusText(
                text = it,
                error = true,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        RouteRuleOutboundTagField(
            outbound = outbound,
            options = effectiveOutboundOptions,
            onOutboundChange = onOutboundChange,
        )
    }
    }
}

@Composable
private fun RouteRuleOutboundTagField(
    outbound: RouteRuleOutboundOption,
    options: List<RouteRuleOutboundOption>,
    onOutboundChange: (RouteRuleOutboundOption) -> Unit,
) {
    val label = stringResource(R.string.routing_outbound_tag_label)
    val tag = outbound.tag.trim()
    val query = outbound.label.trim()
    val suggestions = remember(tag, query, options) {
        if (query.isEmpty() || options.any { it.tag == tag }) {
            options
        } else {
            options.filter { option ->
                option.tag.contains(query, ignoreCase = true) || option.label.contains(query, ignoreCase = true)
            }.ifEmpty { options }
        }
    }
    TextField(
        value = outbound.label,
        onValueChange = { value -> onOutboundChange(RouteRuleOutboundOption(tag = value, label = value)) },
        label = label,
        singleLine = true,
        trailingIcon = {
            IconDropdownMenu(
                imageVector = MiuixIcons.Basic.ArrowUpDown,
                contentDescription = label,
                entries = suggestions.map { option ->
                    IconDropdownMenuEntry(
                        key = option.tag,
                        title = option.label,
                        selected = option.tag == tag,
                        action = option,
                    )
                },
                onAction = onOutboundChange,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun routeDomainInputError(input: String, invalidMessage: String): String? {
    if (input.any(Char::isWhitespace)) return invalidMessage
    if (input.startsWith("regexp:", ignoreCase = true)) {
        return if (input.substringAfter(":").isBlank()) invalidMessage else null
    }
    if (isXrayExternalDomainRuleCandidate(input)) {
        return if (isValidXrayExternalDomainRule(input)) null else invalidMessage
    }

    val supportedPrefix = input.substringBefore(":", missingDelimiterValue = "")
        .lowercase()
        .takeIf { it in setOf("domain", "full", "keyword", "geosite") }
    if (supportedPrefix != null) {
        return if (input.substringAfter(":").isBlank()) invalidMessage else null
    }

    return if (input.contains("://") || input.contains("/")) invalidMessage else null
}

private fun routeIpInputError(input: String, invalidMessage: String): String? {
    val lowerInput = input.lowercase()
    if (lowerInput.startsWith("geoip:") || lowerInput.startsWith("ext:")) {
        return if (input.substringAfter(":").isBlank()) invalidMessage else null
    }
    return if (isIpOrCidrAddress(input)) null else invalidMessage
}

private fun routeProcessInputError(input: String, invalidMessage: String): String? {
    return if (input.any(Char::isWhitespace)) invalidMessage else null
}

private fun isRouteNetworkList(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return true

    val allowedNetworks = setOf("tcp", "udp")
    return trimmed.split(",")
        .map { it.trim().lowercase() }
        .all { it in allowedNetworks }
}
