// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableLazyGridState
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState

internal class AsteriskReorderableLazyListState(
    val reorderableState: ReorderableLazyListState,
    val hapticFeedback: HapticFeedback,
)

internal class AsteriskReorderableLazyGridState(
    val reorderableState: ReorderableLazyGridState,
    val hapticFeedback: HapticFeedback,
)

@Composable
internal fun rememberAsteriskReorderableLazyListState(
    lazyListState: LazyListState,
    itemCount: Int,
    itemIndexOffset: Int = 0,
    scrollThresholdPadding: PaddingValues = PaddingValues(0.dp),
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
): AsteriskReorderableLazyListState {
    val hapticFeedback = LocalHapticFeedback.current
    val reorderableState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
        scrollThresholdPadding = scrollThresholdPadding,
    ) { from, to ->
        val fromIndex = from.index - itemIndexOffset
        val toIndex = to.index - itemIndexOffset
        if (fromIndex == toIndex || fromIndex !in 0 until itemCount || toIndex !in 0 until itemCount) {
            return@rememberReorderableLazyListState
        }

        onMove(fromIndex, toIndex)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    return AsteriskReorderableLazyListState(
        reorderableState = reorderableState,
        hapticFeedback = hapticFeedback,
    )
}

@Composable
internal fun rememberAsteriskReorderableLazyGridState(
    lazyGridState: LazyGridState,
    itemCount: Int,
    itemIndexOffset: Int = 0,
    scrollThresholdPadding: PaddingValues = PaddingValues(0.dp),
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
): AsteriskReorderableLazyGridState {
    val hapticFeedback = LocalHapticFeedback.current
    val reorderableState = rememberReorderableLazyGridState(
        lazyGridState = lazyGridState,
        scrollThresholdPadding = scrollThresholdPadding,
    ) { from, to ->
        val fromIndex = from.index - itemIndexOffset
        val toIndex = to.index - itemIndexOffset
        if (fromIndex == toIndex || fromIndex !in 0 until itemCount || toIndex !in 0 until itemCount) {
            return@rememberReorderableLazyGridState
        }

        onMove(fromIndex, toIndex)
        hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    return AsteriskReorderableLazyGridState(
        reorderableState = reorderableState,
        hapticFeedback = hapticFeedback,
    )
}

@Composable
internal fun rememberReorderableLazyListContentPaddingWithoutTop(
    listPadding: PaddingValues,
): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    val start = listPadding.calculateStartPadding(layoutDirection)
    val end = listPadding.calculateEndPadding(layoutDirection)
    val bottom = listPadding.calculateBottomPadding()
    return remember(start, end, bottom) {
        PaddingValues(
            start = start,
            end = end,
            bottom = bottom,
        )
    }
}

@Composable
internal fun rememberReorderableScrollThresholdPadding(
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
): PaddingValues {
    return remember(top, bottom) {
        PaddingValues(top = top, bottom = bottom)
    }
}

internal fun Modifier.longPressReorderDragHandle(
    scope: ReorderableCollectionItemScope,
    enabled: Boolean,
    state: AsteriskReorderableLazyListState,
): Modifier {
    return with(scope) {
        this@longPressReorderDragHandle.longPressDraggableHandle(
            enabled = enabled,
            onDragStarted = {
                state.hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onDragStopped = {
                state.hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
            },
        )
    }
}

internal fun Modifier.longPressReorderDragHandle(
    scope: ReorderableCollectionItemScope,
    enabled: Boolean,
    state: AsteriskReorderableLazyGridState,
): Modifier {
    return with(scope) {
        this@longPressReorderDragHandle.longPressDraggableHandle(
            enabled = enabled,
            onDragStarted = {
                state.hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onDragStopped = {
                state.hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
            },
        )
    }
}

internal fun <T> List<T>.moveItem(
    fromIndex: Int,
    toIndex: Int,
): List<T> {
    if (fromIndex !in indices || toIndex !in indices || fromIndex == toIndex) {
        return this
    }

    return toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}
