package features.resources.runtime

import android.content.Context
import android.net.Uri
import app.R
import app.ResourceFileKind
import app.ResourceFilesStatus
import app.ResourceFileUpdateSource
import engine.vpn.LocalProxyLoopbackAddress
import engine.vpn.VpnLocalProxyRuntime
import engine.network.NetworkLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import features.resources.ResourceFileUpdateOptions

internal class AndroidResourceFileRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val store = AndroidResourceFileStore(appContext)
    private val downloader = AndroidResourceFileDownloader()

    suspend fun status(): ResourceFilesStatus = withContext(Dispatchers.IO) {
        store.status()
    }

    suspend fun update(
        source: ResourceFileUpdateSource,
        options: ResourceFileUpdateOptions,
    ): ResourceFilesStatus = withContext(Dispatchers.IO) {
        store.dataDir.mkdirs()
        AndroidResourceFileDownloadCancellation.begin()
        val notifier = AndroidResourceFileDownloadNotifier(appContext)
        val downloadProxy = options.toDownloadProxy()
        if (downloadProxy != null) {
            AndroidResourceFileLogger.info(
                "Resource file update will use local proxy ${downloadProxy.host}:${downloadProxy.port}",
            )
        }
        val downloads = listOf(
            ResourceFileKind.GeoIp to source.geoIpUrl,
            ResourceFileKind.GeoSite to source.geoSiteUrl,
        )
        val result = runCatching {
            downloads.forEachIndexed { index, (kind, url) ->
                notifier.showProgress(kind.displayName, progress = null, force = true)
                downloader.download(url, store.file(kind), downloadProxy) { downloadedBytes, totalBytes ->
                    notifier.showProgress(
                        fileName = kind.displayName,
                        progress = overallProgress(
                            fileIndex = index,
                            fileCount = downloads.size,
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                        ),
                    )
                }
                store.applyPermissions(kind)
            }
            store.currentStatus()
        }
        result.onSuccess {
            notifier.showComplete()
        }.onFailure { error ->
            if (error is AndroidResourceFileDownloadCancelledException) {
                AndroidResourceFileLogger.info("Resource file update cancelled")
                notifier.showCancelled()
            } else {
                AndroidResourceFileLogger.error("Failed to update resource files", error)
                notifier.showFailed(error.message ?: error::class.simpleName.orEmpty())
            }
        }
        result.getOrElse { error ->
            if (error is AndroidResourceFileDownloadCancelledException) {
                throw AndroidResourceFileDownloadCancelledException(
                    appContext.getString(R.string.resource_file_download_notification_cancelled),
                )
            }
            throw error
        }
    }

    suspend fun replace(kind: ResourceFileKind, uri: Uri): ResourceFilesStatus = withContext(Dispatchers.IO) {
        store.replace(kind, uri)
        store.currentStatus()
    }

    suspend fun restoreBundled(kind: ResourceFileKind): ResourceFilesStatus = withContext(Dispatchers.IO) {
        store.restoreBundled(kind)
        store.currentStatus()
    }
}

private fun ResourceFileUpdateOptions.toDownloadProxy(): AndroidResourceFileDownloadProxy? {
    if (!useRunningProxy) return null
    val runtimeOptions = VpnLocalProxyRuntime.current()
    val port = runtimeOptions?.port
        ?: fallbackProxyPort?.takeIf { it in NetworkLimits.PORT_MIN..NetworkLimits.PORT_MAX }
        ?: return null
    return AndroidResourceFileDownloadProxy(
        host = LocalProxyLoopbackAddress,
        port = port,
        username = runtimeOptions?.username ?: fallbackProxyUsername,
        password = runtimeOptions?.password ?: fallbackProxyPassword,
    )
}
