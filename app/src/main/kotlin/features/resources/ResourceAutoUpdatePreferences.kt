// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun ResourceAutoUpdatePreferences(
    enabled: Boolean,
    interval: String,
    onEnabledChange: (Boolean) -> Unit,
    onIntervalChange: (String) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val draft = rememberTextFieldState(initialText = interval)
    val valid = resourceAutoUpdateIntervalMillis(true, draft.text.toString()) != null
    SwitchPreference(
        title = stringResource(R.string.settings_resource_files_auto_update_title),
        summary = stringResource(R.string.settings_resource_files_auto_update_summary),
        checked = enabled,
        onCheckedChange = onEnabledChange,
    )
    ArrowPreference(
        title = stringResource(R.string.settings_resource_files_auto_update_interval_title),
        summary = stringResource(R.string.settings_resource_files_auto_update_hours, interval),
        onClick = {
            draft.setTextAndPlaceCursorAtEnd(interval)
            editing = true
        },
    )
    WindowDialog(
        show = editing,
        title = stringResource(R.string.settings_resource_files_auto_update_interval_title),
        onDismissRequest = { editing = false },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextField(
                    state = draft,
                    label = stringResource(R.string.settings_resource_files_auto_update_interval),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!valid) {
                    Text(
                        text = stringResource(R.string.common_error_update_interval),
                        color = MiuixTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TextButton(
                        text = stringResource(R.string.common_cancel),
                        onClick = { editing = false },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = stringResource(R.string.common_save),
                        enabled = valid,
                        onClick = {
                            onIntervalChange(draft.text.toString().trim())
                            editing = false
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    )
}
