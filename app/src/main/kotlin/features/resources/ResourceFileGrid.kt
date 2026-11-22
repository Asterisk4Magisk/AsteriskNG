// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import ui.layout.LocalAdaptiveWindow

@Composable
internal fun useWideResourceLayout(): Boolean {
    val window = LocalAdaptiveWindow.current
    return window.supportsWideContent && window.width >= 720.dp && LocalDensity.current.fontScale <= 1.3f
}

@Composable
internal fun ResourceFileGrid(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    content: LazyGridScope.() -> Unit,
) {
    val minimumWidth = 320.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minimumWidth),
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

internal fun LazyGridScope.resourceGridHeader(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}
