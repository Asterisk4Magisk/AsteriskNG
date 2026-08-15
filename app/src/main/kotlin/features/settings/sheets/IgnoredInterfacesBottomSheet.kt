// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.R
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import ui.components.StringListEditor

@Composable
internal fun IgnoredInterfacesBottomSheet(
    show: Boolean,
    selectedInterfaces: List<String>,
    onSelectedInterfacesChange: (List<String>) -> Unit,
    onDismissRequest: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var editorPending by remember(show) { mutableStateOf(false) }
    val invalidMessage = stringResource(R.string.settings_ignored_interfaces_invalid)

    WindowBottomSheet(
        show = show,
        title = stringResource(R.string.settings_ignored_interfaces),
        startAction = {
            TextButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismissRequest,
            )
        },
        endAction = {
            TextButton(
                text = stringResource(R.string.common_save),
                onClick = { onSave(selectedInterfaces.sanitizeIgnoredInterfaceSelectors()) },
                enabled = !editorPending,
            )
        },
        onDismissRequest = onDismissRequest,
    ) {
        SettingsSheetContent {
            StringListEditor(
                editorKey = "ignored-interfaces:$show",
                title = stringResource(R.string.settings_ignored_interfaces_input),
                description = stringResource(R.string.settings_ignored_interfaces_summary),
                values = selectedInterfaces.sanitizeIgnoredInterfaceSelectors(),
                onValuesChange = {
                    onSelectedInterfacesChange(it.sanitizeIgnoredInterfaceSelectors())
                },
                emptyText = stringResource(R.string.settings_ignored_interfaces_empty),
                validateInput = { value ->
                    if (isIgnoredInterfaceSelector(value)) null else invalidMessage
                },
                onPendingChange = { editorPending = it },
            )
        }
    }
}
