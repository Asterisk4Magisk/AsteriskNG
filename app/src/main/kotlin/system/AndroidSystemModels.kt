// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package system

data class ShellExecOptions(
    val cwd: String? = null,
    val env: Map<String, String> = emptyMap(),
    val logFailure: Boolean = true,
)

data class ShellExecResult(
    val errno: Int,
    val stdout: String,
    val stderr: String,
)

data class InstalledPackageInfo(
    val packageName: String,
    val appLabel: String,
    val isSystem: Boolean,
    val uid: Int,
)

data class AndroidUser(
    val id: Int,
    val name: String,
)

internal fun parseAndroidUsers(output: String): List<AndroidUser> {
    return output
        .lineSequence()
        .mapNotNull { line ->
            val body = line.substringAfter("UserInfo{", missingDelimiterValue = "")
                .substringBefore("}", missingDelimiterValue = "")
            if (body.isBlank()) {
                return@mapNotNull null
            }
            val parts = body.split(":", limit = 3)
            val id = parts.firstOrNull()?.toIntOrNull() ?: return@mapNotNull null
            AndroidUser(
                id = id,
                name = parts.getOrNull(1).orEmpty().ifBlank { "User $id" },
            )
        }
        .distinctBy { it.id }
        .toList()
}
