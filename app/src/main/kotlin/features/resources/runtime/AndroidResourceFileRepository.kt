// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources.runtime

import android.content.Context
import android.net.Uri
import app.R
import app.CustomResourceFileState
import app.ResourceFileKind
import app.ResourceFilesStatus
import app.ResourceFileUpdateSource
import engine.proxy.LocalProxyLoopbackAddress
import engine.proxy.LocalProxyRuntime
import engine.network.isPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import features.resources.ResourceFileUpdateOptions

internal class AndroidResourceFileRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val store = AndroidResourceFileStore(appContext)
    private val downloader = AndroidResourceFileDownloader()

    suspend fun status(customResourceFiles: List<CustomResourceFileState> = emptyList()): ResourceFilesStatus =
        withContext(Dispatchers.IO) {
            store.status(customResourceFiles)
        }

    suspend fun synchronizeBundledFilesAfterPackageUpdate(resourceFileSource: Int) {
        withContext(Dispatchers.IO) {
            store.synchronizeBundledFilesAfterPackageUpdate(resourceFileSource)
        }
    }

    suspend fun deleteCustom(
        customFile: CustomResourceFileState,
        customResourceFiles: List<CustomResourceFileState>,
    ): ResourceFilesStatus = withContext(Dispatchers.IO) {
        store.deleteCustom(customFile)
        store.currentStatus(customResourceFiles)
    }

    suspend fun renameCustom(
        previousFile: CustomResourceFileState,
        customFile: CustomResourceFileState,
        customResourceFiles: List<CustomResourceFileState>,
    ): ResourceFilesStatus = withContext(Dispatchers.IO) {
        store.renameCustom(previousFile, customFile)
        store.currentStatus(customResourceFiles)
    }

    suspend fun update(
        source: ResourceFileUpdateSource,
        options: ResourceFileUpdateOptions,
        customResourceFiles: List<CustomResourceFileState> = emptyList(),
    ): ResourceFilesStatus = withContext(Dispatchers.IO) {
        updateTargets(
            downloads = UpdateableResourceFileKinds.map { kind ->
                kind.toDownloadTarget(source)
            } + customResourceFiles.mapNotNull { customFile -> customFile.toDownloadTargetOrNull() },
            options = options,
            customResourceFiles = customResourceFiles,
        )
    }

    suspend fun update(
        kind: ResourceFileKind,
        source: ResourceFileUpdateSource,
        options: ResourceFileUpdateOptions,
        customResourceFiles: List<CustomResourceFileState> = emptyList(),
    ): ResourceFilesStatus = withContext(Dispatchers.IO) {
        updateTargets(
            downloads = listOf(kind.toDownloadTarget(source)),
            options = options,
            customResourceFiles = customResourceFiles,
        )
    }

    suspend fun updateCustom(
        customFile: CustomResourceFileState,
        options: ResourceFileUpdateOptions,
        customResourceFiles: List<CustomResourceFileState> = emptyList(),
    ): ResourceFilesStatus = withContext(Dispatchers.IO) {
        updateTargets(
            downloads = listOfNotNull(customFile.toDownloadTargetOrNull()),
            options = options,
            customResourceFiles = customResourceFiles,
        )
    }

    private fun updateTargets(
        downloads: List<ResourceFileDownloadTarget>,
        options: ResourceFileUpdateOptions,
        customResourceFiles: List<CustomResourceFileState>,
    ): ResourceFilesStatus {
        if (downloads.isEmpty()) {
            return store.currentStatus(customResourceFiles)
        }
        store.dataDir.mkdirs()
        AndroidResourceFileDownloadCancellation.begin()
        val notifier = AndroidResourceFileDownloadNotifier(appContext)
        val downloadProxy = options.toDownloadProxy()
        if (downloadProxy != null) {
            AndroidResourceFileLogger.info(
                "Resource file update will use local proxy ${downloadProxy.host}:${downloadProxy.port}",
            )
        }
        val userAgent = options.userAgent.ifBlank { null }
        val result = runCatching {
            downloads.forEachIndexed { index, download ->
                try {
                    notifier.showProgress(download.displayName, progress = null, force = true)
                    downloader.download(download.url, download.targetFile, downloadProxy, userAgent) { downloadedBytes, totalBytes ->
                        notifier.showProgress(
                            fileName = download.displayName,
                            progress = overallProgress(
                                fileIndex = index,
                                fileCount = downloads.size,
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                            ),
                        )
                    }
                    download.applyPermissions()
                } catch (error: Throwable) {
                    if (error is AndroidResourceFileDownloadCancelledException) throw error
                    throw ResourceFileDownloadFailedException(download.displayName, error)
                }
            }
            store.currentStatus(customResourceFiles)
        }
        result.onSuccess {
            runCatching { notifier.showComplete() }
        }.onFailure { error ->
            if (error is AndroidResourceFileDownloadCancelledException) {
                AndroidResourceFileLogger.info("Resource file update cancelled")
                runCatching { notifier.showCancelled() }
            } else {
                AndroidResourceFileLogger.error("Failed to update resource files", error)
                runCatching { notifier.showFailed(error.message ?: error::class.simpleName.orEmpty()) }
            }
        }
        return result.getOrElse { error ->
            if (error is AndroidResourceFileDownloadCancelledException) {
                throw AndroidResourceFileDownloadCancelledException(
                    appContext.getString(R.string.resource_file_download_notification_cancelled),
                )
            }
            throw error
        }
    }

    private fun CustomResourceFileState.toDownloadTargetOrNull(): ResourceFileDownloadTarget? {
        val target = store.file(this)
        if (ResourceFileKind.entries.any { kind -> kind.fileName == target.name }) return null
        val updateUrl = url.trim()
        if (updateUrl.isBlank()) return null
        return ResourceFileDownloadTarget(
            displayName = name,
            url = updateUrl,
            targetFile = target,
        )
    }

    private fun ResourceFileKind.toDownloadTarget(source: ResourceFileUpdateSource): ResourceFileDownloadTarget {
        val updateUrl = when (this) {
            ResourceFileKind.GeoIp -> source.geoIpUrl
            ResourceFileKind.GeoSite -> source.geoSiteUrl
            ResourceFileKind.GeoIpOnlyCnPrivate -> source.geoIpOnlyCnPrivateUrl
            ResourceFileKind.DirectCidrIpv4 -> source.directCidrIpv4Url
            ResourceFileKind.DirectCidrIpv6 -> source.directCidrIpv6Url
            ResourceFileKind.XrayCore -> error("${ResourceFileKind.XrayCore.displayName} cannot be updated from URL")
        }
        return ResourceFileDownloadTarget(
            displayName = displayName,
            url = updateUrl,
            targetFile = store.file(this),
            applyPermissions = { store.applyPermissions(this) },
        )
    }

    suspend fun replaceCustom(
        customFile: CustomResourceFileState,
        uri: Uri,
        customResourceFiles: List<CustomResourceFileState> = emptyList(),
    ): ResourceFilesStatus = withContext(Dispatchers.IO) {
        store.replaceCustom(customFile, uri)
        store.currentStatus(customResourceFiles)
    }

    suspend fun replace(
        kind: ResourceFileKind,
        uri: Uri,
        customResourceFiles: List<CustomResourceFileState> = emptyList(),
    ): ResourceFilesStatus = withContext(Dispatchers.IO) {
        store.replace(kind, uri)
        store.currentStatus(customResourceFiles)
    }

    suspend fun restoreBundled(
        kind: ResourceFileKind,
        customResourceFiles: List<CustomResourceFileState> = emptyList(),
    ): ResourceFilesStatus = withContext(Dispatchers.IO) {
        store.restoreBundled(kind)
        store.currentStatus(customResourceFiles)
    }
}

private data class ResourceFileDownloadTarget(
    val displayName: String,
    val url: String,
    val targetFile: java.io.File,
    val applyPermissions: () -> Unit = {},
)

private val UpdateableResourceFileKinds = listOf(
    ResourceFileKind.GeoIp,
    ResourceFileKind.GeoSite,
    ResourceFileKind.GeoIpOnlyCnPrivate,
    ResourceFileKind.DirectCidrIpv4,
    ResourceFileKind.DirectCidrIpv6,
)

private class ResourceFileDownloadFailedException(
    fileName: String,
    cause: Throwable,
) : RuntimeException("$fileName: ${cause.message ?: cause::class.simpleName.orEmpty()}", cause)

private fun ResourceFileUpdateOptions.toDownloadProxy(): AndroidResourceFileDownloadProxy? {
    if (!useRunningProxy) return null
    val runtimeOptions = LocalProxyRuntime.current()
    val port = runtimeOptions?.port
        ?: fallbackProxyPort?.takeIf(Int::isPort)
        ?: error("Local proxy port is unavailable")
    return AndroidResourceFileDownloadProxy(
        host = LocalProxyLoopbackAddress,
        port = port,
        username = runtimeOptions?.username ?: fallbackProxyUsername,
        password = runtimeOptions?.password ?: fallbackProxyPassword,
    )
}
