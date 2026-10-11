// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.enhancement

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.R
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun EnhancementModuleInstallDialog(
    progress: EnhancementModuleInstallProgress,
    onDismiss: () -> Unit,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(progress.output, scroll.maxValue) {
        scroll.scrollTo(scroll.maxValue)
    }
    WindowDialog(
        show = true,
        title = stringResource(R.string.enhancement_module_install_title),
        modifier = Modifier.height(520.dp),
        onDismissRequest = { if (!progress.running) onDismiss() },
    ) {
        BackHandler(enabled = progress.running) {}
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(72.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (progress.running) InfiniteProgressIndicator(size = 24.dp)
                }
                Text(
                    text = enhancementModuleSummary(
                        progress.result ?: EnhancementModuleState(EnhancementModulePhase.Installing),
                    ),
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    style = MiuixTheme.textStyles.body2,
                    color = if (progress.result?.phase == EnhancementModulePhase.Failed) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            }
            SelectionContainer(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.onBackground.copy(alpha = 0.04f)),
            ) {
                Text(
                    text = progress.output.ifEmpty {
                        stringResource(R.string.enhancement_module_install_output_waiting)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(12.dp),
                    style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
                )
            }
            TextButton(
                text = stringResource(R.string.enhancement_module_install_close),
                onClick = onDismiss,
                enabled = !progress.running,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
