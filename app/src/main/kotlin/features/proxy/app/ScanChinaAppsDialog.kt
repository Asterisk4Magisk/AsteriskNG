// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun ScanChinaAppsDialog(
    progress: ScanProgressState?,
    onCancel: () -> Unit,
) {
    if (progress == null) return
    val listState = rememberLazyListState()
    LaunchedEffect(progress.matched.size) {
        if (progress.matched.isNotEmpty()) listState.scrollToItem(progress.matched.lastIndex)
    }
    WindowDialog(
        show = true,
        title = stringResource(R.string.proxy_app_list_scan_china_apps),
        onDismissRequest = onCancel,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(
                    R.string.proxy_app_list_scan_china_progress,
                    progress.scanned, progress.total, progress.matched.size,
                ),
                style = MiuixTheme.textStyles.body2,
            )
            Spacer(Modifier.height(8.dp))
            val ratio = if (progress.total > 0) progress.scanned.toFloat() / progress.total else 0f
            Box(Modifier.fillMaxWidth().height(4.dp).background(MiuixTheme.colorScheme.primary.copy(alpha = 0.16f))) {
                Box(Modifier.fillMaxWidth(ratio.coerceIn(0f, 1f)).height(4.dp).background(MiuixTheme.colorScheme.primary))
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(280.dp).padding(vertical = 12.dp),
                state = listState,
            ) {
                if (progress.matched.isEmpty()) {
                    item { Text(stringResource(R.string.common_empty)) }
                }
                items(progress.matched, key = { it.key }) { item ->
                    Text(
                        text = "${item.label} (${item.packageName})",
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            TextButton(
                text = stringResource(R.string.common_cancel),
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
