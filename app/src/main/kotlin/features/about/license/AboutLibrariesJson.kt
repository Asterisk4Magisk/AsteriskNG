// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.about.license

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class Libs(
    val libraries: List<Library>,
)

@Serializable
data class Library(
    val uniqueId: String,
    val artifactVersion: String? = null,
    val name: String,
    val description: String? = null,
    val website: String? = null,
    val scm: Scm? = null,
    val licenses: List<String> = emptyList(),
)

@Serializable
data class Scm(
    val url: String? = null,
)

fun decodeAboutLibraries(json: String): Libs {
    return aboutLibrariesJson.decodeFromString(json)
}

private val aboutLibrariesJson = Json {
    ignoreUnknownKeys = true
}
