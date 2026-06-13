// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root

import engine.proxy.LocalProxyOptions
import engine.xray.XrayCoreLogPaths
import engine.xray.logDirectoryPath
import java.io.File

internal data class RootStartConfig(
    val xrayConfigJson: String,
    val setuidgidPath: String,
    val runtimeLayout: RootRuntimeLayout,
    val enableIpv6: Boolean,
    val enableFakeDns: Boolean,
    val enableAccessLog: Boolean,
    val coreLogPaths: XrayCoreLogPaths,
) {
    val configPath: String
        get() = runtimeLayout.configPath
}

internal interface RootModeStartConfig {
    val root: RootStartConfig
    val localProxyOptions: LocalProxyOptions
}

internal val RootStartConfig.startupScriptPath: String
    get() = runtimeLayout.startupScriptPath

internal val RootStartConfig.bootLogDirPath: String
    get() = coreLogPaths.logDirectoryPath()

internal val RootStartConfig.bootLogPath: String
    get() = File(bootLogDirPath, RootBootLogFileName).absolutePath

internal val RootRuntimeLayout.startupScriptPath: String
    get() = File(dataDir, RootStartupScriptFileName).absolutePath
