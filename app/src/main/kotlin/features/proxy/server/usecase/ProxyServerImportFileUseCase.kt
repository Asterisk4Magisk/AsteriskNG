// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase

import android.content.Context
import android.net.Uri
import app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ProxyServerImportFileUseCase(
    private val context: Context,
    private val filePicker: suspend () -> Uri?,
) {
    suspend fun readText(): String? {
        val uri = filePicker() ?: return null
        return withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(uri)
                ?: error(context.getString(R.string.error_proxy_server_import_file_open_failed))
            input.use { stream -> stream.readBytes().decodeToString() }
        }
    }
}
