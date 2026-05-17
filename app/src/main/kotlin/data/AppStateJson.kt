package data

import features.logs.AndroidAppLogger
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object StringListJson {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(values: List<String>): String {
        return json.encodeToString(values)
    }

    fun decode(payload: String): List<String> {
        return runCatching {
            json.decodeFromString<List<String>>(payload)
        }.onFailure { error ->
            AndroidAppLogger.warn(LogTag, "Failed to decode persisted string list", error)
        }.getOrDefault(emptyList())
    }

    private const val LogTag = "AppStateJson"
}
