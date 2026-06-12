// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package utils

import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal fun generateQrCodeImageBitmap(
    text: String,
    sizePx: Int,
): ImageBitmap {
    require(text.isNotBlank()) { "QR code text is required" }
    require(sizePx > 0) { "QR code size must be positive" }
    val matrix = MultiFormatWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN to 1,
        ),
    )
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) {
        val offset = y * sizePx
        for (x in 0 until sizePx) {
            pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return createBitmap(sizePx, sizePx)
        .apply { setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx) }
        .asImageBitmap()
}
