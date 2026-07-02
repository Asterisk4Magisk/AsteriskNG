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
    val enableRootIpv6Disabler: Boolean,
    val enableLocalDns: Boolean,
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
    val rootEbpfConfig: RootEbpfRuntimeConfig?
        get() = null
}

internal val RootStartConfig.startupScriptPath: String
    get() = runtimeLayout.startupScriptPath

internal val RootStartConfig.bootLogDirPath: String
    get() = coreLogPaths.logDirectoryPath()

internal val RootStartConfig.bootLogPath: String
    get() = File(bootLogDirPath, RootBootLogFileName).absolutePath

internal val RootRuntimeLayout.startupScriptPath: String
    get() = File(dataDir, RootStartupScriptFileName).absolutePath

internal val RootRuntimeLayout.ipv6DisablerPidPath: String
    get() = File(dataDir, RootIpv6DisablerPidFileName).absolutePath

internal val RootRuntimeLayout.bpfPolicyPath: String
    get() = File(dataDir, RootEbpfPolicyFileName).absolutePath

internal val RootRuntimeLayout.bpf2socksConfigPath: String
    get() = File(dataDir, RootBpf2SocksConfigFileName).absolutePath

internal val RootRuntimeLayout.bpf2socksPidPath: String
    get() = File(dataDir, RootBpf2SocksPidFileName).absolutePath

internal val RootRuntimeLayout.rootEbpfDirectCidrPathV4: String
    get() = File(dataDir, RootEbpfDirectCidrV4FileName).absolutePath

internal val RootRuntimeLayout.rootEbpfDirectCidrPathV6: String
    get() = File(dataDir, RootEbpfDirectCidrV6FileName).absolutePath

internal val RootStartConfig.ipv6DisablerLogPath: String
    get() = coreLogPaths.ipv6DisablerLogFile().absolutePath
