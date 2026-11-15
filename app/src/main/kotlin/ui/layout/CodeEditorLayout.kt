// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
