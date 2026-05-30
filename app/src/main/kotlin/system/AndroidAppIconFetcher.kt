// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package system

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import features.logs.AndroidAppLogger
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import features.proxy.app.model.ProxyAppIconRequest
import androidx.core.graphics.createBitmap

internal class AndroidAppIconFetcher(
    context: Context,
    private val data: ProxyAppIconRequest,
) : Fetcher {
    private val appContext = context.applicationContext

    override suspend fun fetch(): FetchResult? {
        return withContext(Dispatchers.IO) {
            runCatching {
                val packageManager = appContext.packageManager
                val applicationInfo = packageManager.getApplicationInfoCompat(data.packageName)
                ImageFetchResult(
                    image = applicationInfo
                        .loadIcon(packageManager)
                        .toIconBitmap(data.sizePx.coerceAtLeast(1))
                        .asImage(),
                    isSampled = false,
                    dataSource = DataSource.DISK,
                )
            }.onFailure { error ->
                AndroidAppLogger.warn(LogTag, "Failed to load app icon for ${data.packageName}", error)
            }.getOrNull()
        }
    }

    class Factory(
        context: Context,
    ) : Fetcher.Factory<ProxyAppIconRequest> {
        private val appContext = context.applicationContext

        override fun create(
            data: ProxyAppIconRequest,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher {
            return AndroidAppIconFetcher(appContext, data)
        }
    }

    class CacheKeyer : Keyer<ProxyAppIconRequest> {
        override fun key(
            data: ProxyAppIconRequest,
            options: Options,
        ): String {
            return "app-icon:${data.packageName}:${data.sizePx}"
        }
    }

    private companion object {
        private const val LogTag = "AndroidAppIconFetcher"
    }
}

private fun Drawable.toIconBitmap(sizePx: Int): Bitmap {
    val bitmap = createBitmap(sizePx, sizePx)
    val canvas = Canvas(bitmap)
    val oldBounds = Rect(bounds)
    setBounds(0, 0, sizePx, sizePx)
    draw(canvas)
    bounds = oldBounds
    bitmap.prepareToDraw()
    return bitmap
}
