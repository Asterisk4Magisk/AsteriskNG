// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import ui.clipboard.ClipboardImportMode

@Composable
internal fun ImportModeDialog(
    show: Boolean,
    title: String,
    message: String,
    onDismissRequest: () -> Unit,
    onModeSelected: (ClipboardImportMode) -> Unit,
) {
    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.common_merge_import),
                    onClick = { onModeSelected(ClipboardImportMode.Merge) },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.common_replace_existing),
                    onClick = { onModeSelected(ClipboardImportMode.Replace) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
