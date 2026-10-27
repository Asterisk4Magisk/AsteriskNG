// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources.runtime

import app.ProjectInfo
import engine.network.ProxyCredentials
import engine.network.toSocksProxy
import engine.network.withProxyAuthenticator
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import features.resources.runtime.writeResourceAtomically as writeAtomically

internal class AndroidResourceFileDownloader {
    fun download(
        url: String,
        target: File,
        proxy: AndroidResourceFileDownloadProxy? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ) {
        proxy?.withProxyAuthenticator {
            try {
                downloadWithRetries(url, target, proxy, onProgress)
                return
            } catch (_: IOException) {
                AndroidResourceFileLogger.info("Proxy download failed, falling back to direct connection")
            }
        }
        downloadWithRetries(url, target, null, onProgress)
    }

    private fun downloadWithRetries(
        url: String,
        target: File,
        proxy: AndroidResourceFileDownloadProxy?,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        var lastError: Throwable? = null
        repeat(MaxRetries) { attempt ->
            try {
                downloadWithRedirects(url, target, proxy, onProgress)
                return
            } catch (error: Throwable) {
                if (AndroidResourceFileDownloadCancellation.isCancelled()) {
                    throw AndroidResourceFileDownloadCancelledException()
                }
                if (error is IOException && attempt < MaxRetries - 1) {
                    lastError = error
                    Thread.sleep(RetryBackoffMs * (1L shl attempt))
                } else {
                    throw error
                }
            }
        }
        throw lastError ?: error("Download failed")
    }

    private fun downloadWithRedirects(
        url: String,
        target: File,
        proxy: AndroidResourceFileDownloadProxy?,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        var currentUrl = url
        repeat(MaxRedirects) {
            val connection = URI.create(currentUrl).toUrlConnection(proxy)
            try {
                AndroidResourceFileDownloadCancellation.track(connection)
                AndroidResourceFileDownloadCancellation.throwIfCancelled()
                val code = connection.responseCode
                AndroidResourceFileDownloadCancellation.throwIfCancelled()
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: error("Redirect location missing")
                    currentUrl = URI(currentUrl).resolve(location).toString()
                    return@repeat
                }
                if (code !in 200..299) {
                    error("HTTP $code")
                }
                val totalBytes = connection.contentLengthLong
                connection.inputStream.use { input ->
                    writeAtomically(target) { output ->
                        input.copyToWithProgress(output, totalBytes, onProgress)
                    }
                }
                return
            } finally {
                AndroidResourceFileDownloadCancellation.untrack(connection)
                connection.disconnect()
            }
        }
        error("Too many redirects")
    }
}

internal data class AndroidResourceFileDownloadProxy(
    override val host: String,
    override val port: Int,
    override val username: String,
    override val password: String,
) : ProxyCredentials

private fun URI.toUrlConnection(proxy: AndroidResourceFileDownloadProxy?): HttpURLConnection {
    val url = toURL()
    val connection = if (proxy == null) {
        url.openConnection()
    } else {
        url.openConnection(proxy.toSocksProxy())
    }
    return (connection as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        instanceFollowRedirects = false
        requestMethod = "GET"
        setRequestProperty("User-Agent", ResourceFileDefaultUserAgent)
    }
}


private const val MaxRedirects = 5
private const val MaxRetries = 3
private const val RetryBackoffMs = 1000L
private const val ResourceFileDefaultUserAgent = "${ProjectInfo.PROJECT_NAME}/v${ProjectInfo.VERSION_NAME}"

internal fun overallProgress(
    fileIndex: Int,
    fileCount: Int,
    downloadedBytes: Long,
    totalBytes: Long,
): Int? {
    if (totalBytes <= 0L || fileCount <= 0) return null
    val completedFiles = fileIndex.coerceAtLeast(0).toDouble()
    val currentFileProgress = (downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
    return (((completedFiles + currentFileProgress) / fileCount.toDouble()) * 100).toInt().coerceIn(0, 100)
}

private fun java.io.InputStream.copyToWithProgress(
    output: java.io.OutputStream,
    totalBytes: Long,
    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var downloadedBytes = 0L
    onProgress(downloadedBytes, totalBytes)
    while (true) {
        AndroidResourceFileDownloadCancellation.throwIfCancelled()
        val bytesRead = read(buffer)
        if (bytesRead < 0) break
        AndroidResourceFileDownloadCancellation.throwIfCancelled()
        output.write(buffer, 0, bytesRead)
        downloadedBytes += bytesRead
        onProgress(downloadedBytes, totalBytes)
    }
}
