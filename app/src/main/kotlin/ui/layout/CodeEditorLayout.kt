// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.layout

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

@Composable
internal fun codeEditorShowsSupportingContent(editorFocused: Boolean): Boolean {
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    return !editorFocused || !imeVisible
}
