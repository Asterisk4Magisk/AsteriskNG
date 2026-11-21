// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.layout

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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
            ?: emptyFlow<WindowLayoutInfo>()
    }
    val windowLayoutInfo by layoutInfoFlow.collectAsState(initial = null)
    val size = LocalWindowInfo.current.containerSize
    val direction = LocalLayoutDirection.current
    val density = LocalDensity.current
    val pane = windowLayoutInfo?.displayFeatures?.asSequence()?.filterIsInstance<FoldingFeature>()
        ?.mapNotNull { it.usablePane(size, direction) }
        ?.firstOrNull() ?: IntRect(0, 0, size.width, size.height)

    return with(density) {
        val left = pane.left.toDp()
        val right = (size.width - pane.right).toDp()
        AdaptiveWindow(
            width = pane.width.toDp(),
            height = pane.height.toDp(),
            hasSeparatingFold = pane.width < size.width || pane.height < size.height,
            foldPadding = PaddingValues(
                start = if (direction == LayoutDirection.Ltr) left else right,
                top = pane.top.toDp(),
                end = if (direction == LayoutDirection.Ltr) right else left,
                bottom = (size.height - pane.bottom).toDp(),
            ),
        )
    }
}

private fun FoldingFeature.usablePane(size: IntSize, direction: LayoutDirection): IntRect? {
    if (!isSeparating && occlusionType != FoldingFeature.OcclusionType.FULL) return null
    val fold = bounds
    return if (orientation == FoldingFeature.Orientation.VERTICAL) {
        if (fold.left <= 0 || fold.right >= size.width) return null
        val leftPaneWidth = fold.left
        val rightPaneWidth = size.width - fold.right
        if (leftPaneWidth > rightPaneWidth ||
            (leftPaneWidth == rightPaneWidth && direction == LayoutDirection.Ltr)
        ) {
            IntRect(0, 0, fold.left, size.height)
        } else {
            IntRect(fold.right, 0, size.width, size.height)
        }
    } else {
        if (fold.top <= 0 || fold.bottom >= size.height) return null
        // Keep the upper panel on equal halves so the keyboard can open below it.
        if (fold.top >= size.height - fold.bottom) {
            IntRect(0, 0, size.width, fold.top)
        } else {
            IntRect(0, fold.bottom, size.width, size.height)
        }
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

@Composable
internal fun rememberPageGutter(): Dp = when {
    LocalAdaptiveWindow.current.width >= 840.dp -> 24.dp
    LocalAdaptiveWindow.current.width >= 600.dp -> 20.dp
    else -> 16.dp
}
