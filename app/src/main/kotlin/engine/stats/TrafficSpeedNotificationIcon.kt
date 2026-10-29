// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.stats

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Icon
import androidx.core.graphics.createBitmap
import java.util.Locale
import kotlin.math.roundToInt

/** A monochrome notification icon; SystemUI supplies the status bar's foreground color. */
internal class TrafficSpeedNotificationIcon(density: Float) {
    private val size = (24f * density).roundToInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val bounds = Rect()
    private var cachedText: TrafficSpeedIconText? = null
    private var cachedIcon: Icon? = null

    fun create(bytesPerSecond: Long): Icon {
        val text = trafficSpeedIconText(bytesPerSecond)
        cachedIcon?.let { icon -> if (cachedText == text) return icon }

        // Never redraw a bitmap already handed to NotificationManager.
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        drawLine(canvas, text.value, top = 0f, bottom = 15f)
        drawLine(canvas, text.unit, top = 17f, bottom = 24f)
        return Icon.createWithBitmap(bitmap).also { icon ->
            cachedText = text
            cachedIcon = icon
        }
    }

    private fun drawLine(canvas: Canvas, text: String, top: Float, bottom: Float) {
        val scale = size / 24f
        val width = 23f * scale
        val height = (bottom - top) * scale
        paint.textScaleX = 1f
        paint.textSize = height
        paint.getTextBounds(text, 0, text.length, bounds)
        paint.textSize *= height / bounds.height()
        paint.getTextBounds(text, 0, text.length, bounds)
        // SystemUI shrinks the whole icon, so narrow wide text before giving up glyph height.
        if (bounds.width() > width) {
            paint.textScaleX = maxOf(MinTextScaleX, width / bounds.width())
            paint.getTextBounds(text, 0, text.length, bounds)
            if (bounds.width() > width) {
                paint.textSize *= width / bounds.width()
                paint.getTextBounds(text, 0, text.length, bounds)
            }
        }
        canvas.drawText(
            text,
            (size - bounds.width()) / 2f - bounds.left,
            (top + bottom) * scale / 2f - bounds.exactCenterY(),
            paint,
        )
    }
}

internal data class TrafficSpeedIconText(val value: String, val unit: String)

internal fun trafficSpeedIconText(bytesPerSecond: Long): TrafficSpeedIconText {
    var speed = bytesPerSecond.coerceAtLeast(0L).toDouble()
    var unitIndex = 0
    // Binary units like the notification text, capped at three characters so the digits stay large.
    // Promote before rounding to 1000.
    while (speed >= 999.5 && unitIndex < TrafficSpeedUnits.lastIndex) {
        speed /= 1024.0
        unitIndex += 1
    }
    val value = if (unitIndex == 0 || speed >= 9.95) {
        String.format(Locale.ROOT, "%.0f", speed)
    } else {
        String.format(Locale.ROOT, "%.1f", speed)
    }
    return TrafficSpeedIconText(value, TrafficSpeedUnits[unitIndex])
}

private const val MinTextScaleX = 0.8f
private val TrafficSpeedUnits = arrayOf("B/s", "K/s", "M/s", "G/s", "T/s", "P/s", "E/s")
