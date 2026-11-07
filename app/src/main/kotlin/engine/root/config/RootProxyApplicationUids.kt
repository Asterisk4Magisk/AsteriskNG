// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.config

import android.content.Context
import android.os.Process
import app.modes.ProxyAppListModeBlacklist
import app.modes.ProxyAppListModeGlobal
import app.modes.ProxyAppListModeWhitelist
import features.proxy.app.model.toProxyAppSelectionKeyOrNull
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
        val packageKey = key.toProxyAppSelectionKeyOrNull() ?: return@mapNotNull null
        val appId = appIds.getOrPut(packageKey.packageName) {
            runCatching {
                packageManager.getApplicationInfoCompat(packageKey.packageName).uid.toAndroidAppId()
            }.getOrNull()
        } ?: return@mapNotNull null
        packageKey.userIdOr(defaultUserId) * ANDROID_USER_UID_RANGE + appId
    }.distinct()
}
