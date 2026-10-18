// Copyright 2026, Asterisk4Magisk contributors
// SPDX-License-Identifier: GPL-3.0
package features.enhancement

internal interface EnhancementModuleRootAccess {
    suspend fun requireRoot()
    suspend fun execute(command: String): String
    suspend fun executeStreaming(command: String, onOutput: (String) -> Unit): String = execute(command)
}

internal class EnhancementModuleRootBackend(
    private val rootFactory: () -> EnhancementModuleRootAccess,
    private val imageReader: suspend () -> EnhancementModuleImage,
    private val apkPath: String,
    private val executable: String,
) : EnhancementModuleBackend {
    private val root by lazy(rootFactory)
    override suspend fun expectedImage(): EnhancementModuleImage = imageReader()
    override suspend fun inspect(): EnhancementModuleRuntime? {
        root.requireRoot()
        val output = root.execute(enhancementInspectCommand(executable))
        return parseEnhancementModuleRuntime(output)
    }
    override suspend fun reboot() {
        root.requireRoot()
        root.execute("/system/bin/toybox timeout -s TERM -k 2 10 /system/bin/reboot")
    }

    override suspend fun install(onOutput: (String) -> Unit) {
        root.requireRoot()
        val manager = when (root.execute(EnhancementManagerProbeCommand).trim()) {
            "magisk" -> EnhancementRootManager.Magisk
            "ksu" -> EnhancementRootManager.KernelSU
            "apatch" -> EnhancementRootManager.APatch
            else -> throw EnhancementModuleException(EnhancementModuleFailure.UnsupportedManager)
        }
        try {
            root.executeStreaming(enhancementInstallCommand(manager, apkPath), onOutput)
        } catch (error: EnhancementModuleException) {
            if (error.failure == EnhancementModuleFailure.CommandFailed) {
                throw EnhancementModuleException(EnhancementModuleFailure.ApkInstallFailed)
            }
            throw error
        }
    }
}

// Probe known manager CLIs only, with no manager downloads or enable/remove writes.
internal const val EnhancementManagerProbeCommand = "/system/bin/toybox timeout -s TERM -k 2 15 /system/bin/sh -c " +
    "'if [ -x /data/adb/apd ]; then echo apatch; " +
    "elif [ -x /data/adb/ksud ]; then echo ksu; " +
    "elif command -v magisk >/dev/null 2>&1 && magisk -V >/dev/null 2>&1; then echo magisk; " +
    "else echo unsupported; fi'"

internal fun enhancementInspectCommand(executable: String): String {
    require(executable.startsWith('/') && '\u0000' !in executable)
    return "/system/bin/toybox timeout -s TERM -k 2 5 ${executable.enhancementShellQuote()} enhancement-info"
}

internal fun parseEnhancementModuleRuntime(output: String): EnhancementModuleRuntime? {
    val framed = if (output.endsWith("\n")) output else "$output\n"
    if (framed == "AE_UNAVAILABLE\n") return null
    if (framed == "AE_ROOT_DENIED\n") throw EnhancementModuleException(EnhancementModuleFailure.RootDenied)
    try {
        check(framed.length <= 512)
        val match = Regex("AE_INFO 1\nversionCode=([0-9]{1,18})\nversion=([A-Za-z0-9._+\\-]{1,64})\nbuildId=([0-9a-f]{64})\nnetd=([0-9]{1,20})\napi=([0-9]{1,3})\nmodes=([0-9]{1,3})\nflags=([0-9]{1,3})\nprotocol=([0-9]{1,3})\n").matchEntire(framed) ?: error("Invalid INFO")
        val values = match.groupValues
        val image = EnhancementModuleImage(values[1].toLong(), values[2], values[3])
        val netd = values[4].toULong().also { check(it != 0uL) }
        val api = values[5].toInt().also { check(it >= 26) }
        val modes = values[6].toInt().also { check(it in 0..7) }
        val flags = values[7].toInt().also { check(it in 0..15) }
        return EnhancementModuleRuntime(image, netd, api, modes, flags, values[8].toInt())
    } catch (_: Exception) {
        throw EnhancementModuleException(EnhancementModuleFailure.InvalidModule)
    }
}
