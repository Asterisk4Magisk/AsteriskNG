package features.settings.sheets

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.R
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import ui.text.formatTemplate

private data class ExternalInterfaceGroup(
    val key: String,
    val prefixes: List<String>,
)

private val ExternalInterfaceGroups = listOf(
    ExternalInterfaceGroup("wifi", listOf("wlan+", "ap+", "softap+")),
    ExternalInterfaceGroup("usb", listOf("rndis+", "usb+")),
    ExternalInterfaceGroup("bluetooth", listOf("bnep+", "bt-pan+")),
    ExternalInterfaceGroup("ethernet", listOf("eth+")),
)

// Android vendors use different mobile data interface names; keep this list permissive for outlet candidates.
private val IgnoredInterfaceAllowedPrefixes = listOf(
    "wlan",
    "rmnet_data",
    "rmnet",
    "ccmni",
    "cc2mni",
    "ccemni",
    "pdp",
    "ppp",
    "eth",
    "bond",
    "oem",
    "rev_rmnet",
)

// Exclude loopback, virtual, tunnel, and tethering-facing interfaces from outlet candidates.
private val IgnoredInterfaceBlockedPrefixes = listOf(
    "lo",
    "dummy",
    "tun",
    "tap",
    "ifb",
    "ip6tnl",
    "sit",
    "gre",
    "gretap",
    "erspan",
    "veth",
    "br",
    "docker",
    "clat",
    "v4-",
    "ip_vti",
    "rndis",
    "usb",
    "ap",
    "softap",
    "bnep",
    "bt-pan",
    "p2p",
)

@Composable
internal fun externalInterfacesSummary(interfaces: List<String>): String {
    val selected = interfaces.sanitizeExternalInterfaces()
    if (selected.isEmpty()) {
        return stringResource(R.string.settings_external_interfaces_none)
    }
    val selectedGroups = ExternalInterfaceGroups
        .filter { group -> group.prefixes.any { it in selected } }
        .map { group -> externalInterfaceGroupTitle(group) }
    return stringResource(R.string.settings_external_interfaces_selected)
        .formatTemplate("interfaces" to selectedGroups.joinToString())
}

internal fun List<String>.sanitizeExternalInterfaces(): List<String> {
    val selectedPrefixes = map(String::trim).toSet()
    return ExternalInterfaceGroups.flatMap { group ->
        if (group.prefixes.any { it in selectedPrefixes }) group.prefixes else emptyList()
    }
}

@Composable
internal fun ignoredInterfacesSummary(interfaces: List<String>): String {
    if (interfaces.isEmpty()) {
        return stringResource(R.string.settings_ignored_interfaces_none)
    }
    return stringResource(R.string.settings_ignored_interfaces_selected)
        .formatTemplate("interfaces" to interfaces.joinToString())
}

internal fun outletInterfaceOptions(interfaces: List<String>): List<String> {
    return interfaces
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .filter { interfaceName ->
            IgnoredInterfaceBlockedPrefixes.none(interfaceName::startsWith)
        }
        .distinct()
        .sortedWith(
            compareBy<String> { interfaceName ->
                IgnoredInterfaceAllowedPrefixes.indexOfFirst(interfaceName::startsWith)
                    .takeIf { it >= 0 } ?: IgnoredInterfaceAllowedPrefixes.size
            }.thenBy { it },
        )
        .toList()
}

internal fun List<String>.orderedBy(options: List<String>): List<String> {
    return options.filter { it in this }
}

@Composable
internal fun ExternalInterfacesBottomSheet(
    show: Boolean,
    selectedInterfaces: List<String>,
    onSelectedInterfacesChange: (List<String>) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    WindowBottomSheet(
        show = show,
        title = stringResource(R.string.settings_external_interfaces),
        startAction = {
            TextButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismissRequest,
            )
        },
        endAction = {
            TextButton(
                text = stringResource(R.string.common_save),
                onClick = { onSave(selectedInterfaces.sanitizeExternalInterfaces()) },
            )
        },
        onDismissRequest = onDismissRequest,
    ) {
        SettingsSheetContent {
            SheetStatusText(stringResource(R.string.settings_external_interfaces_summary))
            ExternalInterfaceGroups.forEach { group ->
                val sanitizedSelection = selectedInterfaces.sanitizeExternalInterfaces()
                SwitchPreference(
                    title = externalInterfaceGroupTitle(group),
                    summary = group.prefixes.joinToString(),
                    checked = group.prefixes.all { it in sanitizedSelection },
                    onCheckedChange = { enabled ->
                        val next = if (enabled) {
                            sanitizedSelection + group.prefixes
                        } else {
                            sanitizedSelection.filterNot { it in group.prefixes }
                        }
                        onSelectedInterfacesChange(next.sanitizeExternalInterfaces())
                    },
                )
            }
        }
    }
}

@Composable
private fun externalInterfaceGroupTitle(group: ExternalInterfaceGroup): String {
    return when (group.key) {
        "wifi" -> stringResource(R.string.settings_external_interfaces_wifi)
        "usb" -> stringResource(R.string.settings_external_interfaces_usb)
        "bluetooth" -> stringResource(R.string.settings_external_interfaces_bluetooth)
        "ethernet" -> stringResource(R.string.settings_external_interfaces_ethernet)
        else -> group.key
    }
}

@Composable
internal fun IgnoredInterfacesBottomSheet(
    show: Boolean,
    interfaces: List<String>,
    selectedInterfaces: List<String>,
    loading: Boolean,
    errorMessage: String?,
    onSelectedInterfacesChange: (List<String>) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    InterfaceSelectionBottomSheet(
        show = show,
        title = stringResource(R.string.settings_ignored_interfaces),
        description = stringResource(R.string.settings_ignored_interfaces_summary),
        interfaces = interfaces,
        selectedInterfaces = selectedInterfaces,
        loading = loading,
        loadingMessage = stringResource(R.string.settings_ignored_interfaces_loading),
        errorMessage = errorMessage?.let { message ->
            stringResource(R.string.settings_ignored_interfaces_error).formatTemplate("message" to message)
        },
        emptyMessage = stringResource(R.string.settings_ignored_interfaces_empty),
        onSelectedInterfacesChange = onSelectedInterfacesChange,
        onDismissRequest = onDismissRequest,
        onSave = onSave,
    )
}

@Composable
private fun InterfaceSelectionBottomSheet(
    show: Boolean,
    title: String,
    description: String,
    interfaces: List<String>,
    selectedInterfaces: List<String>,
    loading: Boolean,
    loadingMessage: String? = null,
    errorMessage: String?,
    emptyMessage: String?,
    onSelectedInterfacesChange: (List<String>) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val latestInterfaces by rememberUpdatedState(interfaces)
    val latestSelectedInterfaces by rememberUpdatedState(selectedInterfaces)
    val latestLoading by rememberUpdatedState(loading)
    val latestErrorMessage by rememberUpdatedState(errorMessage)
    val latestOnSelectedInterfacesChange by rememberUpdatedState(onSelectedInterfacesChange)
    val latestOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val latestOnSave by rememberUpdatedState(onSave)

    WindowBottomSheet(
        show = show,
        title = title,
        startAction = {
            TextButton(
                text = stringResource(R.string.common_cancel),
                onClick = latestOnDismissRequest,
            )
        },
        endAction = {
            TextButton(
                text = stringResource(R.string.common_save),
                onClick = { latestOnSave(latestSelectedInterfaces) },
            )
        },
        onDismissRequest = latestOnDismissRequest,
    ) {
        SettingsSheetContent {
            SheetStatusText(description)
            if (latestLoading && loadingMessage != null) {
                SheetStatusText(loadingMessage)
            }
            latestErrorMessage?.takeIf(String::isNotBlank)?.let { SheetStatusText(it) }
            if (!latestLoading && latestErrorMessage == null && latestInterfaces.isEmpty() && emptyMessage != null) {
                SheetStatusText(emptyMessage)
            }
            InterfaceOptionGrid(
                interfaces = latestInterfaces,
                selectedInterfaces = latestSelectedInterfaces,
                onSelectedInterfacesChange = latestOnSelectedInterfacesChange,
            )
        }
    }
}

@Composable
private fun SheetStatusText(text: String) {
    Text(
        text = text,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun InterfaceOptionGrid(
    interfaces: List<String>,
    selectedInterfaces: List<String>,
    onSelectedInterfacesChange: (List<String>) -> Unit,
) {
    interfaces.chunked(2).forEach { rowItems ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        ) {
            rowItems.forEachIndexed { index, interfaceName ->
                InterfaceOptionCard(
                    interfaceName = interfaceName,
                    selected = interfaceName in selectedInterfaces,
                    onSelectedChange = { selected ->
                        val next = if (selected) {
                            selectedInterfaces + interfaceName
                        } else {
                            selectedInterfaces - interfaceName
                        }
                        onSelectedInterfacesChange(next)
                    },
                    modifier = Modifier.weight(1f),
                )
                if (index == 0) {
                    Spacer(Modifier.width(8.dp))
                }
            }
            if (rowItems.size == 1) {
                Spacer(Modifier.width(8.dp))
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun InterfaceOptionCard(
    interfaceName: String,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val toggle = { onSelectedChange(!selected) }
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        onClick = toggle,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = interfaceName,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Checkbox(
                state = ToggleableState(selected),
                onClick = toggle,
            )
        }
    }
}
