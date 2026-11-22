// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import ui.layout.LocalAdaptiveWindow
import ui.layout.avoidFold

@Composable
internal fun AdaptiveBottomSheet(
    show: Boolean,
    title: String,
    startAction: @Composable () -> Unit,
    endAction: @Composable () -> Unit,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val window = LocalAdaptiveWindow.current
    val currentContent = rememberUpdatedState(content)
    val sheetContent = remember { movableContentOf { currentContent.value() } }
    if (!window.hasSeparatingFold) {
        WindowBottomSheet(
            show = show,
            title = title,
            startAction = startAction,
            endAction = endAction,
            onDismissRequest = onDismissRequest,
            content = sheetContent,
        )
    } else {
        AnimatedFoldDialog(
            show = show,
            alignment = Alignment.BottomCenter,
            onDismissRequest = onDismissRequest,
        ) {
            Card(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                insideMargin = PaddingValues(horizontal = 12.dp),
                colors = CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.background,
                    contentColor = MiuixTheme.colorScheme.onBackground,
                ),
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        startAction()
                        Text(
                            text = title,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MiuixTheme.textStyles.title4,
                        )
                        endAction()
                    }
                    Box(Modifier.weight(1f, fill = false)) { sheetContent() }
                }
            }
        }
    }
}

@Composable
internal fun AnimatedFoldDialog(
    show: Boolean,
    alignment: Alignment,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = show
    if (!show && visibility.isIdle && !visibility.currentState) return

    val window = LocalAdaptiveWindow.current
    val bottomSheet = alignment == Alignment.BottomCenter
    val sheetPosition = remember { folmeSpring<IntOffset>(damping = 0.9f, response = 0.38f) }
    val sheetEffects = remember { folmeSpring<Float>(damping = 0.9f, response = 0.38f) }
    val dialogEffects = remember { folmeSpring<Float>(damping = 0.9f, response = 0.3f) }
    Dialog(
        onDismissRequest = { if (show) onDismissRequest() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().avoidFold(window).safeDrawingPadding().clipToBounds(),
            contentAlignment = alignment,
        ) {
            AnimatedVisibility(
                visibleState = visibility,
                enter = if (bottomSheet) {
                    slideInVertically(sheetPosition) { it } + fadeIn(sheetEffects)
                } else {
                    scaleIn(dialogEffects, initialScale = 0.94f) + fadeIn(dialogEffects)
                },
                exit = if (bottomSheet) {
                    slideOutVertically(sheetPosition) { it } + fadeOut(sheetEffects)
                } else {
                    scaleOut(dialogEffects, targetScale = 0.94f) + fadeOut(dialogEffects)
                },
                label = "foldedWindow",
            ) {
                content()
            }
        }
    }
}
