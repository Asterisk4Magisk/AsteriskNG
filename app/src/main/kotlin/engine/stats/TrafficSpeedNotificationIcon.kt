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

    fun create(downlinkBytesPerSecond: Long): Icon {
        val text = trafficSpeedIconText(downlinkBytesPerSecond)
        cachedIcon?.let { icon -> if (cachedText == text) return icon }

        // Never redraw a bitmap already handed to NotificationManager.
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        drawLine(canvas, text.value, top = 1f, bottom = 14f)
        drawLine(canvas, text.unit, top = 16f, bottom = 23f)
        return Icon.createWithBitmap(bitmap).also { icon ->
            cachedText = text
            cachedIcon = icon
        }
    }

    private fun drawLine(canvas: Canvas, text: String, top: Float, bottom: Float) {
        val scale = size / 24f
        val height = (bottom - top) * scale
        paint.textSize = height
        paint.getTextBounds(text, 0, text.length, bounds)
        paint.textSize *= minOf(22f * scale / bounds.width(), height / bounds.height())
        paint.getTextBounds(text, 0, text.length, bounds)
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
    // Match the notification's binary byte units. Promote before rounding to 1024.
    while (speed >= 1023.5 && unitIndex < TrafficSpeedUnits.lastIndex) {
        speed /= 1024.0
        unitIndex += 1
    }
    val value = if (unitIndex == 0 || speed >= 99.95) {
        String.format(Locale.ROOT, "%.0f", speed)
    } else {
        String.format(Locale.ROOT, "%.1f", speed)
    }
    return TrafficSpeedIconText(value, TrafficSpeedUnits[unitIndex])
}

private val TrafficSpeedUnits = arrayOf("B/s", "KiB/s", "MiB/s", "GiB/s", "TiB/s", "PiB/s", "EiB/s")
