// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package data.backup

import android.content.Context
import android.net.Uri
import app.AppState
import app.ProjectInfo
import app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class AppBackupUseCase(
    private val context: Context,
    private val filePicker: suspend () -> Uri?,
    private val fileCreator: suspend (String) -> Uri?,
) {
    private val appContext = context.applicationContext

    suspend fun export(state: AppState): Boolean {
        val uri = fileCreator(defaultBackupFileName()) ?: return false
        val backup = state.toAppBackupFile(
            createdAtMillis = System.currentTimeMillis(),
            appVersionName = ProjectInfo.VERSION_NAME,
            appVersionCode = ProjectInfo.VERSION_CODE,
        )
        val content = encodeAppBackup(backup)
        withContext(Dispatchers.IO) {
            val output = appContext.contentResolver.openOutputStream(uri)
                ?: error(appContext.getString(R.string.error_backup_file_open_failed))
            output.writer(Charsets.UTF_8).use { writer ->
                writer.write(content)
            }
        }
        return true
    }

    suspend fun readRestorePreview(): AppBackupRestorePreview? {
        val uri = filePicker() ?: return null
        val content = withContext(Dispatchers.IO) {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: error(appContext.getString(R.string.error_backup_file_open_failed))
            input.use { stream -> stream.readBytes().decodeToString() }
        }
        return decodeAppBackup(content).toRestorePreview()
    }
}

private val AppBackupJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    prettyPrint = true
}

internal fun encodeAppBackup(backup: AppBackupFile): String =
    AppBackupJson.encodeToString(backup)

internal fun decodeAppBackup(content: String): AppBackupFile =
    AppBackupJson.decodeFromString<AppBackupFile>(content)

private fun defaultBackupFileName(): String {
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "asteriskng-backup-$timestamp.json"
}
