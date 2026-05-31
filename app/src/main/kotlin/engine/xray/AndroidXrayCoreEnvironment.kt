// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.xray

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import go.Seq
import libv2ray.Libv2ray
import utils.encodeUrlSafeBase64NoPadding
import java.util.concurrent.atomic.AtomicReference

internal fun Context.initializeAndroidXrayCoreEnvironment(dataDir: String) {
    if (InitializedDataDir.get() == dataDir) {
        return
    }
    synchronized(CoreEnvironmentLock) {
        if (InitializedDataDir.get() == dataDir) {
            return
        }
        Seq.setContext(applicationContext)
        Libv2ray.initCoreEnv(dataDir, xrayCoreBaseKey())
        InitializedDataDir.set(dataDir)
    }
}

@SuppressLint("HardwareIds")
private fun Context.xrayCoreBaseKey(): String {
    val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        .orEmpty()
        .ifBlank { packageName }
    return deviceId.toByteArray(Charsets.UTF_8)
        .copyOf(XrayCoreBaseKeyLength)
        .encodeUrlSafeBase64NoPadding()
}

private const val XrayCoreBaseKeyLength = 32
private val InitializedDataDir = AtomicReference<String?>()
private val CoreEnvironmentLock = Any()
