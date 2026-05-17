package engine.xray

import android.content.Context
import android.provider.Settings
import android.util.Base64
import go.Seq
import libv2ray.Libv2ray
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

private fun Context.xrayCoreBaseKey(): String {
    val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        .orEmpty()
        .ifBlank { packageName }
    return Base64.encodeToString(
        deviceId.toByteArray(Charsets.UTF_8).copyOf(XrayCoreBaseKeyLength),
        Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE,
    )
}

private const val XrayCoreBaseKeyLength = 32
private val InitializedDataDir = AtomicReference<String?>()
private val CoreEnvironmentLock = Any()
