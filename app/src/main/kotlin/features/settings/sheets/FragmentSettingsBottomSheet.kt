// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

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
import engine.xray.DefaultFragmentInterval
import engine.xray.DefaultFragmentLength
import engine.xray.DefaultFragmentPackets
import engine.xray.FragmentPacketsValues
import engine.xray.MaxFragmentInputLength
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
internal fun fragmentSettingsSummary(
    enabled: Boolean,
    packets: String,
    length: String,
    interval: String,
): String {
    if (!enabled) {
        return stringResource(R.string.settings_fragment_none)
    }
    return stringResource(R.string.settings_fragment_selected).formatTemplate(
        "packets" to normalizeFragmentPackets(packets),
        "length" to normalizeFragmentRange(length, DefaultFragmentLength, min = 1),
        "interval" to normalizeFragmentRange(interval, DefaultFragmentInterval, min = 0),
    )
}

internal fun sanitizeFragmentRangeInput(input: String): String {
    return input
        .filter { char -> char.isDigit() || char == '-' }
        .take(MaxFragmentInputLength)
}

@Composable
internal fun FragmentSettingsBottomSheet(
    show: Boolean,
    enabled: Boolean,
    packets: String,
    length: String,
    interval: String,
    onEnabledChange: (Boolean) -> Unit,
    onPacketsChange: (String) -> Unit,
    onLengthChange: (String) -> Unit,
    onIntervalChange: (String) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (Boolean, String, String, String) -> Unit,
) {
    val lengthError = enabled && !isFragmentRangeValid(length, min = 1)
    val intervalError = enabled && !isFragmentRangeValid(interval, min = 0)
    val canSave = !enabled || (!lengthError && !intervalError)
    val saveSettings = {
        if (canSave) {
            onSave(
                enabled,
                normalizeFragmentPackets(packets),
                normalizeFragmentRange(length, DefaultFragmentLength, min = 1),
                normalizeFragmentRange(interval, DefaultFragmentInterval, min = 0),
            )
        }
    }

    WindowBottomSheet(
        show = show,
        title = stringResource(R.string.settings_fragment),
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
                FragmentStatusText(stringResource(R.string.settings_fragment_description))
                SwitchPreference(
                    title = stringResource(R.string.settings_fragment_enabled),
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                )
                AnimatedVisibility(
                    visible = enabled,
                    enter = fadeIn() + expandVertically(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        WindowDropdownPreference(
                            title = stringResource(R.string.settings_fragment_packets),
                            items = FragmentPacketsValues,
                            selectedIndex = fragmentPacketsIndex(packets),
                            onSelectedIndexChange = { index ->
                                onPacketsChange(FragmentPacketsValues[index.coerceIn(FragmentPacketsValues.indices)])
                            },
                        )
                        FragmentTextField(
                            value = length,
                            onValueChange = onLengthChange,
                            label = stringResource(R.string.settings_fragment_length),
                            errorText = if (lengthError) {
                                stringResource(R.string.settings_fragment_length_error)
                            } else {
                                null
                            },
                        )
                        FragmentTextField(
                            value = interval,
                            onValueChange = onIntervalChange,
                            label = stringResource(R.string.settings_fragment_interval),
                            errorText = if (intervalError) {
                                stringResource(R.string.settings_fragment_interval_error)
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FragmentTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    errorText: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        SheetTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            modifier = Modifier.fillMaxWidth(),
            sanitizeInput = ::sanitizeFragmentRangeInput,
        )
        errorText?.let { FragmentStatusText(text = it, error = true) }
    }
}

@Composable
private fun FragmentStatusText(
    text: String,
    error: Boolean = false,
) {
    Text(
        text = text,
        color = if (error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private fun isFragmentRangeValid(
    value: String,
    min: Int,
): Boolean {
    return parseFragmentRange(value, min) != null
}

private fun normalizeFragmentPackets(value: String): String {
    val normalized = value.trim().lowercase()
    return normalized.takeIf { it in FragmentPacketsValues } ?: DefaultFragmentPackets
}

private fun fragmentPacketsIndex(value: String): Int {
    val index = FragmentPacketsValues.indexOf(normalizeFragmentPackets(value))
    return index.coerceAtLeast(0)
}

private fun normalizeFragmentRange(
    value: String,
    fallback: String,
    min: Int,
): String {
    val range = parseFragmentRange(value, min) ?: return fallback
    return range.end?.let { end -> "${range.start}-$end" } ?: range.start.toString()
}

private data class FragmentRange(
    val start: Int,
    val end: Int?,
)

private fun parseFragmentRange(
    value: String,
    min: Int,
): FragmentRange? {
    val parts = value.trim().split("-")
    if (parts.size !in 1..2 || parts.any(String::isBlank)) {
        return null
    }

    val start = parts[0].toIntOrNull() ?: return null
    val end = if (parts.size == 2) parts[1].toIntOrNull() ?: return null else start
    if (start < min || end < min || start > end) {
        return null
    }

    return FragmentRange(
        start = start,
        end = end.takeIf { it != start },
    )
}
