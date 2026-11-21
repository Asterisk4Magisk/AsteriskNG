// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
    itemCount: Int,
    key: (Int) -> Any,
    modifier: Modifier = Modifier,
    largestSectionItemCount: Int = itemCount,
    state: LazyGridState = rememberLazyGridState(),
    content: @Composable LazyGridItemScope.(index: Int, cardModifier: Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val spacing = 12.dp
        val preferredCardWidth = 320.dp * fontScale.coerceAtMost(1.3f)
        val visibleColumns = if (maxWidth >= preferredCardWidth * 3 + spacing * 2) 3 else 2
        val rowCount = if (itemCount <= visibleColumns) 1 else 2
        val occupiedColumns = maxOf(minOf(itemCount, visibleColumns), (itemCount + rowCount - 1) / rowCount)
        val pairedItemCount = 2 * (itemCount - occupiedColumns)
        val columnWidth = (maxWidth - spacing * (visibleColumns - 1)) / visibleColumns
        // Reserve the same space in both sections when either grid scrolls horizontally.
        val previewWidth = if (largestSectionItemCount > visibleColumns * 2) {
            ((columnWidth - 280.dp) * visibleColumns).coerceIn(0.dp, 32.dp)
        } else {
            0.dp
        }
        val cardWidth = columnWidth - previewWidth / visibleColumns
        val rowHeight = if (cardWidth >= 368.dp && fontScale <= 1.3f) {
            124.dp * fontScale
        } else {
            164.dp * fontScale
        }
        val cardModifier = Modifier.width(cardWidth).wrapContentHeight(Alignment.Top).height(rowHeight)
        LazyHorizontalGrid(
            rows = GridCells.Fixed(rowCount),
            state = state,
            modifier = Modifier.fillMaxWidth().height(rowHeight * rowCount + spacing * (rowCount - 1)),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            items(
                count = itemCount,
                key = key,
                // Keep unpaired cards in their own columns and align them with the top row.
                span = { index -> GridItemSpan(if (index < pairedItemCount) 1 else rowCount) },
            ) { index -> content(index, cardModifier) }
        }
    }
}
