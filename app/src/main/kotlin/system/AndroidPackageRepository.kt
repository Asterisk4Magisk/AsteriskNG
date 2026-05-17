// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package system

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.os.UserManager
import features.logs.AndroidAppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidPackageRepository(
    context: Context,
    private val exec: suspend (String) -> ShellExecResult,
    private val listAndroidUsers: suspend () -> List<AndroidUser>,
) {
    private val appContext = context.applicationContext

    suspend fun listPackages(type: String): List<String> {
        return listPackageEntries(type)
            .map { entry -> entry.packageName }
            .distinct()
            .sorted()
    }

    suspend fun getPackagesInfo(packages: List<String>): List<InstalledPackageInfo> = withContext(Dispatchers.IO) {
        val packageSet = packages.toSet()
        if (packageSet.isEmpty()) {
            return@withContext emptyList()
        }

        val packageManager = appContext.packageManager
        val packageEntries = listPackageEntries(ANDROID_PACKAGE_SCOPE_ALL)
            .filter { entry -> entry.packageName in packageSet }
            .distinctBy { entry -> "${entry.userId}:${entry.uid}:${entry.packageName}" }
        val applicationInfoCache = HashMap<String, ApplicationInfo?>()

        packageEntries.map { entry ->
            val applicationInfo = applicationInfoCache.getOrPut(entry.packageName) {
                runCatching { packageManager.getApplicationInfoCompat(entry.packageName) }
                    .onFailure { error ->
                        AndroidAppLogger.warn(LogTag, "Failed to load application info for ${entry.packageName}", error)
                    }
                    .getOrNull()
            }
            InstalledPackageInfo(
                packageName = entry.packageName,
                appLabel = applicationInfo?.loadLabel(packageManager)?.toString() ?: entry.packageName,
                isSystem = applicationInfo?.isSystemApp() == true,
                uid = entry.uid,
            )
        }.sortedByAppIdentity()
    }

    suspend fun getCurrentUserPackagesInfo(type: String): List<InstalledPackageInfo> = withContext(Dispatchers.IO) {
        val packageManager = appContext.packageManager
        packageManager.getInstalledApplicationsCompat()
            .mapNotNull { applicationInfo ->
                val matchesPackageScope = when (type) {
                    ANDROID_PACKAGE_SCOPE_SYSTEM -> applicationInfo.isSystemApp()
                    ANDROID_PACKAGE_SCOPE_USER -> !applicationInfo.isSystemApp()
                    else -> true
                }
                if (!matchesPackageScope) {
                    return@mapNotNull null
                }
                InstalledPackageInfo(
                    packageName = applicationInfo.packageName,
                    appLabel = applicationInfo.loadLabel(packageManager).toString(),
                    isSystem = applicationInfo.isSystemApp(),
                    uid = applicationInfo.uid,
                )
            }
            .distinctBy { info -> "${info.uid}:${info.packageName}" }
            .sortedByAppIdentity()
    }

    private suspend fun listPackageEntries(type: String): List<ListedPackage> {
        val users = runCatching { listAndroidUsers() }.getOrElse {
            AndroidAppLogger.warn(LogTag, "Failed to list Android users, falling back to current user", it)
            listOf(appContext.currentAndroidUser())
        }
        return users.flatMap { user ->
            val result = exec(packageListCommand(type, user.id))
            if (result.errno == 0) {
                parsePackageList(user.id, result.stdout)
            } else {
                AndroidAppLogger.warn(
                    LogTag,
                    "Failed to list packages for user ${user.id}: ${result.stderr.ifBlank { "errno=${result.errno}" }}",
                )
                emptyList()
            }
        }
    }

    private companion object {
        private const val LogTag = "AndroidPackageRepository"
    }
}

internal fun Context.currentAndroidUser(): AndroidUser {
    val userId = Process.myUid().toAndroidUserId()
    val userManager = getSystemService(Context.USER_SERVICE) as UserManager
    return AndroidUser(
        id = userId,
        name = userManager.userName.ifBlank { "User $userId" },
    )
}

private data class ListedPackage(
    val packageName: String,
    val userId: Int,
    val uid: Int,
)

private fun packageListCommand(type: String, userId: Int): String {
    val scopeFlag = when (type) {
        ANDROID_PACKAGE_SCOPE_SYSTEM -> "-s"
        ANDROID_PACKAGE_SCOPE_USER -> "-3"
        ANDROID_PACKAGE_SCOPE_ALL -> null
        else -> null
    }
    return buildList {
        add("cmd package list packages")
        scopeFlag?.let(::add)
        add("-U")
        add("--user")
        add(userId.toString())
    }.joinToString(" ")
}

private fun parsePackageList(userId: Int, output: String): List<ListedPackage> {
    return output.lineSequence()
        .mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            val packageName = parts.firstOrNull { part -> part.startsWith("package:") }
                ?.removePrefix("package:")
                ?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val uid = parts.firstOrNull { part -> part.startsWith("uid:") }
                ?.removePrefix("uid:")
                ?.toIntOrNull()
                ?: return@mapNotNull null
            ListedPackage(
                packageName = packageName,
                userId = userId,
                uid = uid,
            )
        }
        .toList()
}

private fun ApplicationInfo.isSystemApp(): Boolean {
    return flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
        flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
}

private fun List<InstalledPackageInfo>.sortedByAppIdentity(): List<InstalledPackageInfo> {
    return sortedWith(
        compareBy<InstalledPackageInfo> { info -> info.uid }
            .thenBy { info -> info.appLabel },
    )
}
