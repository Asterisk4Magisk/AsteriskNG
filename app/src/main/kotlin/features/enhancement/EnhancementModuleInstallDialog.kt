// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.enhancement

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
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
    rebooting: Boolean,
    onReboot: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(progress.output, scroll.maxValue) {
        scroll.scrollTo(scroll.maxValue)
    }
    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing
    val availableHeight = LocalWindowInfo.current.containerSize.height -
        insets.getTop(density) - insets.getBottom(density)
    val maxHeight = (with(density) { availableHeight.toDp() } - 48.dp).coerceAtLeast(0.dp)
    WindowDialog(
        show = true,
        modifier = Modifier.heightIn(max = maxHeight),
        maxWidth = 880.dp,
        outsideMargin = DpSize(24.dp, 24.dp),
        insideMargin = DpSize(20.dp, 20.dp),
        onDismissRequest = { if (!progress.running && !rebooting) onDismiss() },
    ) {
        BackHandler(enabled = progress.running || rebooting) {}
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.enhancement_module_install_title),
                    style = MiuixTheme.textStyles.title4,
                    textAlign = TextAlign.Start,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(modifier = Modifier.size(24.dp)) {
                    if (progress.running) InfiniteProgressIndicator(size = 24.dp)
                }
            }
            Text(
                text = enhancementModuleSummary(
                    progress.result ?: EnhancementModuleState(EnhancementModulePhase.Installing),
                ),
                modifier = Modifier.fillMaxWidth().heightIn(max = 72.dp)
                    .verticalScroll(rememberScrollState()),
                textAlign = TextAlign.Start,
                style = MiuixTheme.textStyles.body2,
                color = if (progress.result?.phase == EnhancementModulePhase.Failed) {
                    MiuixTheme.colorScheme.error
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            SelectionContainer(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(min = 360.dp, max = 560.dp)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!progress.running && progress.result?.phase == EnhancementModulePhase.PendingReboot) {
                    TextButton(
                        text = stringResource(R.string.enhancement_module_reboot_action),
                        onClick = onReboot,
                        enabled = !rebooting,
                        modifier = Modifier.weight(1f),
                    )
                }
                TextButton(
                    text = stringResource(R.string.common_close),
                    onClick = onDismiss,
                    enabled = !progress.running && !rebooting,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
