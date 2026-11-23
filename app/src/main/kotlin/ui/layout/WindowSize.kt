// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.layout

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.flow.emptyFlow

internal data class AdaptiveWindow(
    val width: Dp = 0.dp,
    val height: Dp = 0.dp,
    val foldPadding: PaddingValues = PaddingValues(),
    val companionPadding: PaddingValues? = null,
    val hasSeparatingFold: Boolean = false,
) {
    val showNavigationRail: Boolean
        get() = width >= 840.dp || (width >= 600.dp && height / width < 1.2f)
    val supportsWideContent: Boolean get() = width >= 600.dp && height >= 480.dp
    val showTwoColumns: Boolean get() = width >= 840.dp && height >= 480.dp
}

internal val LocalAdaptiveWindow = staticCompositionLocalOf { AdaptiveWindow() }

@Composable
internal fun rememberAdaptiveWindow(): AdaptiveWindow {
    val activity = LocalActivity.current
    val layoutInfoFlow = remember(activity) {
        activity?.let { WindowInfoTracker.getOrCreate(it).windowLayoutInfo(it) }
            ?: emptyFlow()
    }
    val windowLayoutInfo by layoutInfoFlow.collectAsState(initial = null)
    val size = LocalWindowInfo.current.containerSize
    val direction = LocalLayoutDirection.current
    val density = LocalDensity.current
    val panes = separatingWindowPanes(windowLayoutInfo, size)

    return with(density) {
        val minimumPaneWidth = 320.dp.roundToPx()
        val useCompanionPane = panes?.vertical == true && size.height >= 320.dp.roundToPx() &&
            minOf(panes.first.width, panes.second.width) >= minimumPaneWidth
        // Minor differences after rotation should not move controls across the hinge.
        val tolerance = if (useCompanionPane) 48.dp.roundToPx() else 0
        val pane = panes?.primary(direction, tolerance) ?: IntRect(0, 0, size.width, size.height)
        val companion = if (useCompanionPane) panes.other(pane) else null
        fun IntRect.toPadding() = PaddingValues(
            start = (if (direction == LayoutDirection.Ltr) left else size.width - right).toDp(),
            top = top.toDp(),
            end = (if (direction == LayoutDirection.Ltr) size.width - right else left).toDp(),
            bottom = (size.height - bottom).toDp(),
        )
        AdaptiveWindow(
            width = pane.width.toDp(),
            height = pane.height.toDp(),
            foldPadding = pane.toPadding(),
            companionPadding = companion?.toPadding(),
            hasSeparatingFold = panes != null,
        )
    }
}

internal fun usableWindowPane(info: WindowLayoutInfo?, size: IntSize, direction: LayoutDirection): IntRect =
    separatingWindowPanes(info, size)?.primary(direction) ?: IntRect(0, 0, size.width, size.height)

private fun separatingWindowPanes(info: WindowLayoutInfo?, size: IntSize): SeparatingWindowPanes? =
    info?.displayFeatures?.asSequence()?.filterIsInstance<FoldingFeature>()
        ?.firstNotNullOfOrNull { it.windowPanes(size) }

private data class SeparatingWindowPanes(val first: IntRect, val second: IntRect, val vertical: Boolean) {
    fun primary(direction: LayoutDirection, startTolerance: Int = 0): IntRect {
        if (!vertical) {
            // Keep the upper panel on equal halves so the keyboard can open below it.
            return if (first.height >= second.height) first else second
        }
        val start = if (direction == LayoutDirection.Ltr) first else second
        val end = other(start)
        return if (start.width + startTolerance >= end.width) start else end
    }

    fun other(pane: IntRect): IntRect = if (pane == first) second else first
}

private fun FoldingFeature.windowPanes(size: IntSize): SeparatingWindowPanes? {
    if (!isSeparating && occlusionType != FoldingFeature.OcclusionType.FULL) return null
    val fold = bounds
    return if (orientation == FoldingFeature.Orientation.VERTICAL) {
        if (fold.left <= 0 || fold.right >= size.width) return null
        SeparatingWindowPanes(
            IntRect(0, 0, fold.left, size.height),
            IntRect(fold.right, 0, size.width, size.height),
            vertical = true,
        )
    } else {
        if (fold.top <= 0 || fold.bottom >= size.height) return null
        SeparatingWindowPanes(
            IntRect(0, 0, size.width, fold.top),
            IntRect(0, fold.bottom, size.width, size.height),
            vertical = false,
        )
    }
}

@Composable
internal fun FoldCompanionPane(window: AdaptiveWindow, content: @Composable () -> Unit) {
    val padding = window.companionPadding ?: return
    val direction = LocalLayoutDirection.current
    Box(
        modifier = Modifier.fillMaxSize().absolutePadding(
            left = padding.calculateLeftPadding(direction),
            top = padding.calculateTopPadding(),
            right = padding.calculateRightPadding(direction),
            bottom = padding.calculateBottomPadding(),
        ).consumeWindowInsets(padding).safeDrawingPadding().clipToBounds(),
    ) {
        content()
    }
}

@Composable
internal fun Modifier.avoidFold(window: AdaptiveWindow): Modifier {
    val padding = window.foldPadding
    val direction = LocalLayoutDirection.current
    return absolutePadding(
        left = padding.calculateLeftPadding(direction),
        top = padding.calculateTopPadding(),
        right = padding.calculateRightPadding(direction),
        bottom = padding.calculateBottomPadding(),
    ).consumeWindowInsets(padding)
}
