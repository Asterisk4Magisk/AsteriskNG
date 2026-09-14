// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.app

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * Detects Chinese applications by inspecting package name, declared components,
 * and DEX bytecode for vendor prefixes that are characteristic of SDKs developed
 * by, or commonly bundled in, applications distributed from mainland China.
 *
 * The heuristic is intentionally conservative: matches fall through four layers,
 * any positive match short-circuits to `true`, any clear negative match (whitelist
 * or firebase resource) short-circuits to `false`.
 *
 * The single source of truth for the prefix list is [CHINA_APP_PREFIX_LIST].
 */
internal object AppScanner {
    private const val TAG = "AsteriskNG-AppScanner"

    private const val LARGE_DEX_SIZE_BYTES: Long = 15_000_000L

    /**
     * Skip these prefixes outright. Anything matching is treated as definitively
     * non-Chinese even if other heuristics would otherwise flag it.
     */
    private val SKIP_PREFIX_LIST: List<String> = listOf(
        "com.google",
        "com.android.chrome",
        "com.android.vending",
        "com.microsoft",
        "com.apple",
        "com.zhiliaoapp.musically", // TikTok: banned in mainland China.
        "com.android.providers.downloads", // System download manager; may embed Chinese SDK.
    )

    /**
     * Vendor and SDK prefixes characteristic of Chinese vendors, security
     * packers, analytics providers, and platform-specific OEM extensions.
     *
     * Adding an entry here automatically widens detection across package name,
     * declared components, and DEX class signatures.
     */
    private val CHINA_APP_PREFIX_LIST: List<String> = listOf(
        "com.tencent",
        "com.alibaba",
        "com.umeng",
        "com.qihoo",
        "com.ali",
        "com.alipay",
        "com.amap",
        "com.sina",
        "com.weibo",
        "com.vivo",
        "com.xiaomi",
        "com.huawei",
        "com.taobao",
        "com.secneo",
        "s.h.e.l.l",
        "com.stub",
        "com.kiwisec",
        "com.secshell",
        "com.wrapper",
        "cn.securitystack",
        "com.mogosec",
        "com.secoen",
        "com.netease",
        "com.mx",
        "com.qq.e",
        "com.baidu",
        "com.bytedance",
        "com.bugly",
        "com.miui",
        "com.oppo",
        "com.coloros",
        "com.iqoo",
        "com.meizu",
        "com.gionee",
        "cn.nubia",
        "com.oplus",
        "andes.oplus",
        "com.unionpay",
        "cn.wps",
    )

    private val chinaAppRegex: Regex by lazy {
        ("(" + CHINA_APP_PREFIX_LIST.joinToString("|").replace(".", "\\.") + ").*").toRegex()
    }

    /**
     * Returns true when the given package is judged to be a Chinese application.
     */
    suspend fun isChinaApp(packageName: String, packageManager: PackageManager): Boolean {
        currentCoroutineContext().ensureActive()
        SKIP_PREFIX_LIST.forEach { skip ->
            if (packageName == skip || packageName.startsWith("$skip.")) {
                return false
            }
        }

        if (packageName.matches(chinaAppRegex)) {
            Log.d(TAG, "Match package name: $packageName")
            return true
        }

        try {
            val packageManagerFlags =
                PackageManager.MATCH_UNINSTALLED_PACKAGES or
                    PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_SERVICES or
                    PackageManager.GET_RECEIVERS or
                    PackageManager.GET_PROVIDERS

            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(packageManagerFlags.toLong()),
                )
            } else {
                packageManager.getPackageInfo(packageName, packageManagerFlags)
            }

            packageInfo.services?.forEach { service ->
                if (service.name.matches(chinaAppRegex)) {
                    Log.d(TAG, "Match service ${service.name} in $packageName")
                    return true
                }
            }
            packageInfo.activities?.forEach { activity ->
                if (activity.name.matches(chinaAppRegex)) {
                    Log.d(TAG, "Match activity ${activity.name} in $packageName")
                    return true
                }
            }
            packageInfo.receivers?.forEach { receiver ->
                if (receiver.name.matches(chinaAppRegex)) {
                    Log.d(TAG, "Match receiver ${receiver.name} in $packageName")
                    return true
                }
            }
            packageInfo.providers?.forEach { provider ->
                if (provider.name.matches(chinaAppRegex)) {
                    Log.d(TAG, "Match provider ${provider.name} in $packageName")
                    return true
                }
            }

            val publicSourceDir = packageInfo.applicationInfo?.publicSourceDir ?: return false
            ZipFile(File(publicSourceDir)).use { zip ->
                // Cheap negative signal: a firebase resource hints Google ecosystem.
                for (entry in zip.entries()) {
                    currentCoroutineContext().ensureActive()
                    if (entry.name.startsWith("firebase-")) {
                        return false
                    }
                }
                for (entry in zip.entries()) {
                    currentCoroutineContext().ensureActive()
                    if (!(entry.name.startsWith("classes") && entry.name.endsWith(".dex"))) {
                        continue
                    }
                    if (entry.size > LARGE_DEX_SIZE_BYTES) {
                        Log.d(TAG, "Confirm $packageName due to large dex file")
                        return true
                    }
                    val input = BufferedInputStream(zip.getInputStream(entry), 65536)
                    val dexFile = try {
                        input.use { DexBackedDexFile.fromInputStream(null, it) }
                    } catch (error: Exception) {
                        Log.e(TAG, "Error reading dex file for $packageName", error)
                        return false
                    }
                    for (clazz in dexFile.classes) {
                        currentCoroutineContext().ensureActive()
                        val rawType = clazz.type
                        val clazzName = rawType
                            .substring(1, rawType.length - 1)
                            .replace("/", ".")
                            .replace("$", ".")
                        if (clazzName.matches(chinaAppRegex)) {
                            Log.d(TAG, "Match $clazzName in $packageName")
                            return true
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Error scanning package $packageName", error)
        }
        return false
    }
}
