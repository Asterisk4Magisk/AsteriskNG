// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import android.content.Context
import android.net.Uri
import app.ResourceFileKind
import app.ResourceFilesStatus
import app.ResourceFileUpdateSource
import features.resources.runtime.AndroidResourceFileRepository

class ResourceFileUseCase(
    context: Context,
    private val resourceFilePicker: suspend () -> Uri?,
) {
    private val repository = AndroidResourceFileRepository(context.applicationContext)

    suspend fun status(): ResourceFilesStatus {
        return repository.status()
    }

    suspend fun update(
        source: ResourceFileUpdateSource,
        options: ResourceFileUpdateOptions = ResourceFileUpdateOptions(),
    ): ResourceFilesStatus {
        return repository.update(source, options)
    }

    suspend fun replace(kind: ResourceFileKind): ResourceFilesStatus? {
        val uri = resourceFilePicker() ?: return null
        return repository.replace(kind, uri)
    }

    suspend fun restoreBundled(kind: ResourceFileKind): ResourceFilesStatus {
        return repository.restoreBundled(kind)
    }
}

data class ResourceFileUpdateOptions(
    val useRunningProxy: Boolean = false,
    val fallbackProxyPort: Int? = null,
    val fallbackProxyUsername: String = "",
    val fallbackProxyPassword: String = "",
)
