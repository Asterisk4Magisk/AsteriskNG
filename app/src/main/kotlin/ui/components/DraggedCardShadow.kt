// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

fun Modifier.draggedCardShadow(
    alpha: Float,
    color: Color,
): Modifier {
    if (alpha <= 0f) return this

    return drawBehind {
        val cornerRadius = CardDefaults.CornerRadius.toPx()
        val maxSpread = 12.dp.toPx()
        val steps = 12

        for (step in steps downTo 1) {
            val progress = step / steps.toFloat()
            val spread = maxSpread * progress
            val layerAlpha = alpha * 0.035f * (1f - (step - 1f) / steps)

            drawRoundRect(
                color = color.copy(alpha = layerAlpha),
                topLeft = Offset(-spread, -spread),
                size = Size(
                    width = size.width + spread * 2,
                    height = size.height + spread * 2,
                ),
                cornerRadius = CornerRadius(
                    x = cornerRadius + spread,
                    y = cornerRadius + spread,
                ),
            )
        }
    }
}

@Stable
internal class DraggedCardLift(
    val isDragging: Boolean,
    private val scaleState: State<Float>,
    val shadowAlpha: Float,
    val shadowColor: Color,
) {
    val scale: Float
        get() = scaleState.value
}

/** Animate the scale and glow of a card while it is dragged; [label] prefixes both animations. */
@Composable
internal fun animateDraggedCardLift(isDragging: Boolean, label: String): DraggedCardLift {
    val scale = animateFloatAsState(
        targetValue = if (isDragging) 1.025f else 1f,
        animationSpec = folmeSpring(damping = 0.9f, response = 0.38f),
        label = "${label}Scale",
    )
    val shadowAlpha by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = folmeSpring(damping = 0.9f, response = 0.38f),
        label = "${label}ShadowAlpha",
    )
    return DraggedCardLift(
        isDragging = isDragging,
        scaleState = scale,
        shadowAlpha = shadowAlpha,
        shadowColor = MiuixTheme.colorScheme.primary,
    )
}

/** Draw a dragged card above its siblings, scaled, with the dragged glow behind it. */
internal fun Modifier.draggedCardLift(lift: DraggedCardLift): Modifier =
    zIndex(if (lift.isDragging) 1f else 0f)
        .graphicsLayer {
            scaleX = lift.scale
            scaleY = lift.scale
        }
        .draggedCardShadow(
            alpha = lift.shadowAlpha,
            color = lift.shadowColor,
        )
