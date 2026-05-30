// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

fun decodeQrCodeFromImage(
    context: Context,
    uri: Uri,
): String? {
    val bitmap = loadQrBitmap(context, uri)
    return try {
        decodeQrBitmap(bitmap)
    } finally {
        bitmap.recycle()
    }
}

private fun loadQrBitmap(context: Context, uri: Uri): Bitmap {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        loadQrBitmapWithImageDecoder(context, uri)
    } else {
        loadQrBitmapWithBitmapFactory(context, uri)
    }
}

@RequiresApi(Build.VERSION_CODES.P)
private fun loadQrBitmapWithImageDecoder(context: Context, uri: Uri): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetSampleSize(calculateSampleSize(info.size.width, info.size.height))
    }
}

private fun loadQrBitmapWithBitmapFactory(context: Context, uri: Uri): Bitmap {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, bounds)
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
    }
    return resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, options)
    } ?: error("Unable to decode QR image")
}

private fun decodeQrBitmap(bitmap: Bitmap): String? {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) {
        return null
    }

    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val source = RGBLuminanceSource(width, height, pixels)
    val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )
    val reader = MultiFormatReader()
    return listOf(
        BinaryBitmap(HybridBinarizer(source)),
        BinaryBitmap(GlobalHistogramBinarizer(source)),
    ).firstNotNullOfOrNull { binaryBitmap ->
        runCatching {
            reader.decode(binaryBitmap, hints).text
        }.also {
            reader.reset()
        }.getOrNull()
    }
}

private fun calculateSampleSize(width: Int, height: Int): Int {
    if (width <= 0 || height <= 0) {
        return 1
    }

    var sampleSize = 1
    while (maxOf(width, height) / (sampleSize * 2) >= MaxQrBitmapSize) {
        sampleSize *= 2
    }
    return sampleSize
}

private const val MaxQrBitmapSize = 2048
