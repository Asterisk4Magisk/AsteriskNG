// Copyright 2026, Asterisk4Magisk contributors
// SPDX-License-Identifier: GPL-3.0
package features.enhancement

internal enum class EnhancementRootManager { Magisk, KernelSU, APatch }
internal fun enhancementInstallCommand(manager: EnhancementRootManager, apkPath: String): String {
    require(apkPath.startsWith('/') && '\u0000' !in apkPath)
    val command = when (manager) {
        EnhancementRootManager.Magisk -> "magisk --install-module"
        EnhancementRootManager.KernelSU -> "'/data/adb/ksud' module install"
        EnhancementRootManager.APatch -> "'/data/adb/apd' module install"
    }
    return "/system/bin/toybox timeout -s TERM -k 5 120 $command ${apkPath.enhancementShellQuote()} 2>&1"
}

internal fun String.enhancementShellQuote(): String = "'" + replace("'", "'\\''") + "'"
