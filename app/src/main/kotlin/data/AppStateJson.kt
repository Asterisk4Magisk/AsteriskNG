// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package data

import app.CustomResourceFileState
import app.sanitizeCustomResourceFileName
import features.logs.AndroidAppLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val appStateJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

internal object StringListJson {
    fun encode(values: List<String>): String {
        return appStateJson.encodeToString(values)
    }

    fun decode(payload: String): List<String> {
        return runCatching {
            appStateJson.decodeFromString<List<String>>(payload)
        }.onFailure { error ->
            AndroidAppLogger.warn(LogTag, "Failed to decode persisted string list", error)
        }.getOrDefault(emptyList())
    }

    private const val LogTag = "AppStateJson"
}

internal object CustomResourceFileListJson {
    fun encode(values: List<CustomResourceFileState>): String {
        return appStateJson.encodeToString(values.map(PersistedCustomResourceFile::from))
    }

    fun decode(payload: String): List<CustomResourceFileState> {
        return runCatching {
            appStateJson.decodeFromString<List<PersistedCustomResourceFile>>(payload)
                .mapNotNull(PersistedCustomResourceFile::toStateOrNull)
        }.onFailure { error ->
            AndroidAppLogger.warn(LogTag, "Failed to decode persisted custom resource files", error)
        }.getOrDefault(emptyList())
    }

    private const val LogTag = "AppStateJson"
}

@Serializable
private data class PersistedCustomResourceFile(
    val id: Int,
    val name: String,
    val url: String,
) {
    fun toStateOrNull(): CustomResourceFileState? {
        val fileName = sanitizeCustomResourceFileName(name, fallback = "")
        val updateUrl = url.trim()
        if (id <= 0 || fileName.isBlank()) return null
        return CustomResourceFileState(
            id = id,
            name = fileName,
            url = updateUrl,
        )
    }

    companion object {
        fun from(state: CustomResourceFileState): PersistedCustomResourceFile {
            return PersistedCustomResourceFile(
                id = state.id,
                name = state.name,
                url = state.url,
            )
        }
    }
}
