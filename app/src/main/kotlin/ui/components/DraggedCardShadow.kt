// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CardDefaults

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
