// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.layout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntSizeAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState


internal class CodeEditorFocusState(
    val expanded: Boolean,
    val exit: () -> Unit,
)

@Composable
internal fun rememberCodeEditorFocusState(
    editorFocused: Boolean,
    clearEditorFocus: () -> Unit,
    searchActive: Boolean = false,
    backEnabled: Boolean = true,
): CodeEditorFocusState {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(editorFocused) {
        if (editorFocused) expanded = true
    }
    val exit = {
        clearEditorFocus()
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        expanded = false
    }
    val backState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = backState,
        isBackEnabled = backEnabled && expanded && !searchActive,
        onBackCompleted = exit,
    )
    return CodeEditorFocusState(expanded, exit)
}

internal fun codeEditorFocusFade() = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

internal fun codeEditorFocusSize() = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntSize.VisibilityThreshold,
)

@Composable
internal fun CodeEditorFocusLayout(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    supportingContent: @Composable ColumnScope.() -> Unit,
    editor: @Composable (Modifier) -> Unit,
) {
    val fade = codeEditorFocusFade()
    val size = codeEditorFocusSize()
    Column(modifier.imePadding()) {
        AnimatedVisibility(
            visible = !expanded,
            enter = fadeIn(fade) + expandVertically(size),
            exit = fadeOut(fade) + shrinkVertically(size),
            label = "code-editor-supporting-content",
        ) {
            Column(Modifier.fillMaxWidth(), content = supportingContent)
        }
        editor(Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
internal fun CodeEditorPaneLayout(
    sidebarVisible: Boolean,
    animateFocusLayout: Boolean,
    content: @Composable RowScope.() -> Unit,
) {
    if (animateFocusLayout) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val density = LocalDensity.current
            val targetWidth = if (sidebarVisible) minOf(maxWidth, 1080.dp) else maxWidth
            val size by animateIntSizeAsState(
                targetValue = IntSize(with(density) { targetWidth.roundToPx() }, 0),
                animationSpec = codeEditorFocusSize(),
                label = "code-editor-pane-width",
            )
            Row(
                modifier = Modifier.width(with(density) { size.width.toDp() }).fillMaxHeight(),
                content = content,
            )
        }
    } else {
        Row(
            modifier = if (sidebarVisible) Modifier.pageReadableWidth(maxWidth = 1080.dp).fillMaxHeight()
            else Modifier.fillMaxSize(),
            content = content,
        )
    }
}
