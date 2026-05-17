package features.routing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.R
import engine.network.isIpOrCidrAddress
import engine.network.isPortList
import features.routing.model.RouteRule
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ui.components.StringListEditor
import ui.components.StringListStatusText
import ui.components.sanitizeStringListItems

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
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    text = rule.remarks,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
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
            IconButton(onClick = onEdit) {
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

@Composable
internal fun RouteRuleEditorBottomSheet(
    show: Boolean,
    initialRule: RouteRule?,
    nextRuleId: Int,
    outboundOptions: List<RouteRuleOutboundOption>,
    onDismissRequest: () -> Unit,
    onSave: (RouteRule) -> Unit,
) {
    val newRuleName = stringResource(R.string.routing_new_rule)
    val unnamedRuleName = stringResource(R.string.routing_unnamed_rule)
    var remarks by remember(initialRule, show, newRuleName) { mutableStateOf(initialRule?.remarks ?: newRuleName) }
    var domains by remember(initialRule, show) { mutableStateOf(initialRule?.domain ?: listOf("geosite:category")) }
    var ips by remember(initialRule, show) { mutableStateOf(initialRule?.ip ?: emptyList()) }
    var ports by remember(initialRule, show) { mutableStateOf(initialRule?.port ?: "") }
    var process by remember(initialRule, show) { mutableStateOf(initialRule?.process ?: emptyList()) }
    var protocol by remember(initialRule, show) { mutableStateOf(initialRule?.protocol ?: "") }
    var network by remember(initialRule, show) { mutableStateOf(initialRule?.network ?: "") }
    val canSave = isPortList(ports) && isRouteNetworkList(network)
    val currentOutbound = initialRule?.outboundTag?.takeIf { it.isNotBlank() }
    val effectiveOutboundOptions = remember(outboundOptions, currentOutbound) {
        if (currentOutbound != null && outboundOptions.none { it.tag == currentOutbound }) {
            outboundOptions + RouteRuleOutboundOption(tag = currentOutbound, label = currentOutbound)
        } else {
            outboundOptions
        }
    }
    var outboundIndex by remember(initialRule, show, effectiveOutboundOptions) {
        mutableIntStateOf(
            effectiveOutboundOptions
                .indexOfFirst { it.tag == (currentOutbound ?: "proxy") }
                .coerceAtLeast(0),
        )
    }
    val selectedOutboundIndex = outboundIndex.coerceIn(0, effectiveOutboundOptions.lastIndex)
    val saveRule = {
        if (canSave) {
            onSave(
                RouteRule(
                    id = initialRule?.id ?: nextRuleId,
                    remarks = remarks.ifBlank { unnamedRuleName },
                    outboundTag = effectiveOutboundOptions[selectedOutboundIndex].tag,
                    domain = domains.sanitizeStringListItems(),
                    ip = ips.sanitizeStringListItems(),
                    port = ports.trim(),
                    process = process.sanitizeStringListItems(),
                    protocol = protocol.trim(),
                    network = network.trim(),
                    enabled = initialRule?.enabled ?: true,
                ),
            )
        }
    }

    OverlayBottomSheet(
        show = show,
        title = if (initialRule == null) {
            stringResource(R.string.routing_add_rule)
        } else {
            stringResource(R.string.routing_edit_rule)
        },
        startAction = {
            TextButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismissRequest,
            )
        },
        endAction = {
            TextButton(
                text = stringResource(R.string.common_save),
                onClick = saveRule,
            )
        },
        onDismissRequest = onDismissRequest,
        defaultWindowInsetsPadding = false,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            item {
                RouteRuleEditorContent(
                    editorKey = "${initialRule?.id ?: nextRuleId}:$show",
                    remarks = remarks,
                    domains = domains,
                    ips = ips,
                    process = process,
                    ports = ports,
                    protocol = protocol,
                    network = network,
                    effectiveOutboundOptions = effectiveOutboundOptions,
                    selectedOutboundIndex = selectedOutboundIndex,
                    onRemarksChange = { remarks = it },
                    onDomainsChange = { domains = it },
                    onIpsChange = { ips = it },
                    onProcessChange = { process = it },
                    onPortsChange = { ports = it },
                    onProtocolChange = { protocol = it },
                    onNetworkChange = { network = it },
                    onOutboundIndexChange = { outboundIndex = it },
                )
            }
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
    RouteRuleLine(label, values.sanitizeStringListItems().joinToString(", "))
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
    selectedOutboundIndex: Int,
    onRemarksChange: (String) -> Unit,
    onDomainsChange: (List<String>) -> Unit,
    onIpsChange: (List<String>) -> Unit,
    onProcessChange: (List<String>) -> Unit,
    onPortsChange: (String) -> Unit,
    onProtocolChange: (String) -> Unit,
    onNetworkChange: (String) -> Unit,
    onOutboundIndexChange: (Int) -> Unit,
) {
    val duplicateMessage = stringResource(R.string.routing_list_duplicate)
    val emptyMessage = stringResource(R.string.routing_list_empty)
    val domainInvalidMessage = stringResource(R.string.routing_domain_invalid)
    val ipInvalidMessage = stringResource(R.string.routing_ip_invalid)
    val processInvalidMessage = stringResource(R.string.routing_process_invalid)
    val portInvalidMessage = stringResource(R.string.routing_port_invalid)
    val networkInvalidMessage = stringResource(R.string.routing_network_invalid)
    val portError = if (ports.isBlank() || isPortList(ports)) null else portInvalidMessage
    val networkError = if (network.isBlank() || isRouteNetworkList(network)) null else networkInvalidMessage

    Column {
        TextField(
            value = remarks,
            onValueChange = onRemarksChange,
            label = stringResource(R.string.routing_rule_name),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        )
        StringListEditor(
            editorKey = editorKey,
            title = stringResource(R.string.routing_domain_label),
            inputLabel = stringResource(R.string.routing_domain_input),
            values = domains,
            onValuesChange = onDomainsChange,
            emptyText = emptyMessage,
            duplicateText = duplicateMessage,
            validateInput = { routeDomainInputError(it, domainInvalidMessage) },
            modifier = Modifier.padding(bottom = 12.dp),
        )
        StringListEditor(
            editorKey = editorKey,
            title = stringResource(R.string.routing_ip_label),
            inputLabel = stringResource(R.string.routing_ip_input),
            values = ips,
            onValuesChange = onIpsChange,
            emptyText = emptyMessage,
            duplicateText = duplicateMessage,
            validateInput = { routeIpInputError(it, ipInvalidMessage) },
            modifier = Modifier.padding(bottom = 12.dp),
        )
        StringListEditor(
            editorKey = editorKey,
            title = stringResource(R.string.routing_process_label),
            inputLabel = stringResource(R.string.routing_process_input),
            values = process,
            onValuesChange = onProcessChange,
            emptyText = emptyMessage,
            duplicateText = duplicateMessage,
            validateInput = { routeProcessInputError(it, processInvalidMessage) },
            modifier = Modifier.padding(bottom = 12.dp),
        )
        TextField(
            value = ports,
            onValueChange = onPortsChange,
            label = stringResource(R.string.routing_port_label),
            singleLine = true,
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
            value = protocol,
            onValueChange = onProtocolChange,
            label = stringResource(R.string.routing_protocol_label),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        )
        TextField(
            value = network,
            onValueChange = onNetworkChange,
            label = stringResource(R.string.routing_network_label),
            singleLine = true,
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
        OverlayDropdownPreference(
            title = stringResource(R.string.routing_outbound_tag_label),
            items = effectiveOutboundOptions.map { option -> option.label },
            selectedIndex = selectedOutboundIndex,
            onSelectedIndexChange = onOutboundIndexChange,
        )
    }
}

private fun routeDomainInputError(input: String, invalidMessage: String): String? {
    if (input.any(Char::isWhitespace)) return invalidMessage
    if (input.startsWith("regexp:", ignoreCase = true)) {
        return if (input.substringAfter(":").isBlank()) invalidMessage else null
    }

    val supportedPrefix = input.substringBefore(":", missingDelimiterValue = "")
        .lowercase()
        .takeIf { it in setOf("domain", "full", "keyword", "geosite", "ext") }
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
