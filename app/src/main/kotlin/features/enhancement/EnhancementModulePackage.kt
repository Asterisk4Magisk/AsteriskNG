// Copyright 2026, Asterisk4Magisk contributors
// SPDX-License-Identifier: GPL-3.0
package features.enhancement

import java.io.File
import java.util.zip.ZipFile

internal const val EnhancementModuleId = "zygisk-next-asterisk-enhance"
internal val EnhancementModuleLibraryNames = listOf("libasterisk_enhance.so", "libae_resolver29.so")

internal fun readEnhancementModuleImage(archive: File, supportedAbis: List<String>): EnhancementModuleImage {
    try {
        return ZipFile(archive).use { zip ->
            val metadata = zip.getEntry("module.prop") ?: error("Missing metadata")
            val properties = zip.getInputStream(metadata).use { input ->
                val bytes = input.readBytesLimited(16 * 1024)
                parseEnhancementModuleProperties(bytes.toString(Charsets.UTF_8))
            }
            // ROOT system processes use the system primary ABI even when this app
            // was installed from a 32-bit JNI split on a 64-bit Android device.
            val abi = supportedAbis.firstOrNull() ?: error("Unsupported ABI")
            check(abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"))
            val payloadPrefix = "assets/enhance/lib/$abi"
            val prefix = if (zip.entries().asSequence().any { it.name.startsWith("assets/enhance/lib/") }) payloadPrefix else "lib/$abi"
            check(EnhancementModuleLibraryNames.all { zip.getEntry("$prefix/$it")?.size in 1L..(64L * 1024 * 1024) }) { "Missing or invalid primary ABI library" }
            val identity = zip.getEntry("$prefix/runtime-identity.prop") ?: error("Missing runtime identity")
            val buildId = zip.getInputStream(identity).use { it.readBytesLimited(128).toString(Charsets.UTF_8) }
            check(buildId.matches(Regex("buildId=[0-9a-f]{64}\n")))
            EnhancementModuleImage(properties.first, properties.second, buildId.substringAfter('=').trim())
        }
    } catch (error: Exception) {
        throw EnhancementModuleException(EnhancementModuleFailure.InvalidPackage)
    }
}

internal fun parseEnhancementModuleProperties(text: String): Pair<Long, String> {
    val values = linkedMapOf<String, String>()
    text.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.forEach { line ->
        val key = line.substringBefore('=').trim()
        if (key in listOf("id", "version", "versionCode")) {
            check('=' in line && values.put(key, line.substringAfter('=').trim()) == null)
        }
    }
    check(values["id"] == EnhancementModuleId)
    val versionCode = requireNotNull(values["versionCode"]?.toLongOrNull())
    check(versionCode >= 0)
    val version = requireNotNull(values["version"]?.takeIf { it.isNotEmpty() })
    return versionCode to version
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        check(output.size() + count <= limit)
        output.write(buffer, 0, count)
    }
}
