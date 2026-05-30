package features.settings.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import engine.xray.DefaultMuxConcurrency
import engine.xray.DefaultMuxXudpConcurrency
import engine.xray.MaxMuxConcurrency
import engine.xray.MaxMuxXudpConcurrency
import engine.xray.MuxUdp443Values
import app.R
import androidx.compose.ui.res.stringResource
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import ui.text.formatTemplate

@Composable
internal fun muxSettingsSummary(
    enabled: Boolean,
    concurrency: String,
    xudpConcurrency: String,
    xudpProxyUdp443: Int,
): String {
    if (!enabled) {
        return stringResource(R.string.settings_mux_none)
    }
    return stringResource(R.string.settings_mux_selected).formatTemplate(
        "tcp" to muxConcurrencyDisplay(concurrency),
        "xudp" to muxXudpConcurrencyDisplay(xudpConcurrency),
        "udp443" to muxUdp443Options()[sanitizeMuxUdp443Index(xudpProxyUdp443)],
    )
}

internal fun sanitizeMuxIntegerInput(input: String): String {
    return input
        .filterIndexed { index, char -> char.isDigit() || (char == '-' && index == 0) }
        .take(5)
}

internal fun sanitizeMuxUdp443Index(index: Int): Int {
    return index.coerceIn(MuxUdp443Values.indices)
}

@Composable
internal fun MuxSettingsBottomSheet(
    show: Boolean,
    enabled: Boolean,
    concurrency: String,
    xudpConcurrency: String,
    xudpProxyUdp443: Int,
    onEnabledChange: (Boolean) -> Unit,
    onConcurrencyChange: (String) -> Unit,
    onXudpConcurrencyChange: (String) -> Unit,
    onXudpProxyUdp443Change: (Int) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (Boolean, String, String, Int) -> Unit,
) {
    val concurrencyError = enabled && !isMuxConcurrencyValid(concurrency)
    val xudpConcurrencyError = enabled && !isMuxXudpConcurrencyValid(xudpConcurrency)
    val canSave = !enabled || (!concurrencyError && !xudpConcurrencyError)
    val saveSettings = {
        if (canSave) {
            onSave(
                enabled,
                normalizeMuxInteger(concurrency, fallback = DefaultMuxConcurrency),
                normalizeMuxInteger(xudpConcurrency, fallback = DefaultMuxXudpConcurrency),
                sanitizeMuxUdp443Index(xudpProxyUdp443),
            )
        }
    }

    WindowBottomSheet(
        show = show,
        title = stringResource(R.string.settings_mux),
        startAction = {
            TextButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismissRequest,
            )
        },
        endAction = {
            TextButton(
                text = stringResource(R.string.common_save),
                onClick = saveSettings,
            )
        },
        onDismissRequest = onDismissRequest,
    ) {
        key(show) {
            SettingsSheetContent {
                MuxStatusText(stringResource(R.string.settings_mux_description))
                SwitchPreference(
                    title = stringResource(R.string.settings_mux_enabled),
                    summary = stringResource(R.string.settings_mux_enabled_summary),
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                )
                AnimatedVisibility(
                    visible = enabled,
                    enter = fadeIn() + expandVertically(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        MuxNumberField(
                            value = concurrency,
                            onValueChange = onConcurrencyChange,
                            label = stringResource(R.string.settings_mux_concurrency),
                            helpText = stringResource(R.string.settings_mux_concurrency_summary),
                            errorText = if (concurrencyError) {
                                stringResource(R.string.settings_mux_concurrency_error)
                            } else {
                                null
                            },
                        )
                        MuxNumberField(
                            value = xudpConcurrency,
                            onValueChange = onXudpConcurrencyChange,
                            label = stringResource(R.string.settings_mux_xudp_concurrency),
                            helpText = stringResource(R.string.settings_mux_xudp_concurrency_summary),
                            errorText = if (xudpConcurrencyError) {
                                stringResource(R.string.settings_mux_xudp_concurrency_error)
                            } else {
                                null
                            },
                        )
                        WindowDropdownPreference(
                            title = stringResource(R.string.settings_mux_udp443),
                            items = muxUdp443Options(),
                            selectedIndex = sanitizeMuxUdp443Index(xudpProxyUdp443),
                            onSelectedIndexChange = onXudpProxyUdp443Change,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MuxNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    helpText: String,
    errorText: String?,
) {
    SheetTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        sanitizeInput = ::sanitizeMuxIntegerInput,
    )
    MuxStatusText(helpText)
    errorText?.let { MuxStatusText(text = it, error = true) }
}

@Composable
private fun MuxStatusText(
    text: String,
    error: Boolean = false,
) {
    Text(
        text = text,
        color = if (error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun muxUdp443Options(): List<String> {
    return listOf(
        stringResource(R.string.settings_mux_udp443_reject),
        stringResource(R.string.settings_mux_udp443_allow),
        stringResource(R.string.settings_mux_udp443_skip),
    )
}

@Composable
private fun muxConcurrencyDisplay(value: String): String {
    val concurrency = value.toIntOrNull()
    return when {
        concurrency == null -> stringResource(R.string.settings_mux_display_default_8)
        concurrency < 0 -> stringResource(R.string.settings_mux_display_disabled)
        concurrency == 0 -> stringResource(R.string.settings_mux_display_default_8)
        else -> concurrency.toString()
    }
}

@Composable
private fun muxXudpConcurrencyDisplay(value: String): String {
    val concurrency = value.toIntOrNull()
    return when {
        concurrency == null -> stringResource(R.string.settings_mux_display_same_as_tcp)
        concurrency < 0 -> stringResource(R.string.settings_mux_display_disabled)
        concurrency == 0 -> stringResource(R.string.settings_mux_display_same_as_tcp)
        else -> concurrency.toString()
    }
}

private fun isMuxConcurrencyValid(value: String): Boolean {
    val concurrency = value.toIntOrNull() ?: return false
    return concurrency in -1..MaxMuxConcurrency
}

private fun isMuxXudpConcurrencyValid(value: String): Boolean {
    val concurrency = value.toIntOrNull() ?: return false
    return concurrency in -1..MaxMuxXudpConcurrency
}

private fun normalizeMuxInteger(value: String, fallback: String = ""): String {
    return value.trim().toIntOrNull()?.toString() ?: fallback
}
