// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.config

import android.content.Context
import android.os.Process
import app.modes.ProxyAppListModeBlacklist
import app.modes.ProxyAppListModeGlobal
import app.modes.ProxyAppListModeWhitelist
import system.ANDROID_USER_UID_RANGE
import system.getApplicationInfoCompat
import system.toAndroidAppId
import system.toAndroidUserId

internal fun Int.toRootProxyAppListMode(): Int {
    return when (this) {
        ProxyAppListModeBlacklist,
        ProxyAppListModeWhitelist,
        ProxyAppListModeGlobal -> this

        else -> ProxyAppListModeGlobal
    }
}

internal fun Context.resolveRootProxyApplicationUids(packageKeys: List<String>): List<Int> {
    val defaultUserId = Process.myUid().toAndroidUserId()
    val appIds = mutableMapOf<String, Int?>()
    return packageKeys.mapNotNull { key ->
        val packageKey = key.toRootProxyAppPackageKey(defaultUserId) ?: return@mapNotNull null
        val appId = appIds.getOrPut(packageKey.packageName) {
            runCatching {
                packageManager.getApplicationInfoCompat(packageKey.packageName).uid.toAndroidAppId()
            }.getOrNull()
        } ?: return@mapNotNull null
        packageKey.userId * ANDROID_USER_UID_RANGE + appId
    }.distinct()
}

private data class RootProxyAppPackageKey(
    val userId: Int,
    val packageName: String,
)

private fun String.toRootProxyAppPackageKey(defaultUserId: Int): RootProxyAppPackageKey? {
    val normalized = trim().takeIf(String::isNotEmpty) ?: return null
    val separatorIndex = normalized.indexOf(':')
    if (separatorIndex < 0) {
        return RootProxyAppPackageKey(
            userId = defaultUserId,
            packageName = normalized,
        )
    }
    val packageName = normalized.substring(separatorIndex + 1).trim().takeIf(String::isNotEmpty) ?: return null
    return RootProxyAppPackageKey(
        userId = normalized.substring(0, separatorIndex).toIntOrNull() ?: return null,
        packageName = packageName,
    )
}
