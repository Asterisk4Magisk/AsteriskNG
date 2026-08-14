// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.config

import engine.proxy.LocalProxyOptions
import engine.network.NetworkLimits
import engine.root.daemon.config.AsteriskdConfig

const val RootBpf2SocksDefaultBridgePort = NetworkLimits.PORT_MAX - 3
const val DefaultRootHttpProxyPort = NetworkLimits.PORT_MAX - 2
internal const val DefaultRootTun2SocksProxyPort = NetworkLimits.PORT_MAX - 1

internal data class RootConfigRuntimePaths(
    val coreExecutablePath: String,
    val coreConfigPath: String,
    val matcherExecutablePath: String,
    val bpf2SocksExecutablePath: String,
    val hevSocks5TunnelExecutablePath: String,
    val workingDirectory: String,
    val statePath: String,
    val logPath: String,
)

internal data class RootStartConfig(
    val xrayConfigJson: String,
    val publicationStagingDirectory: String,
    val runtimePaths: RootConfigRuntimePaths,
    val directCidrIpv4Path: String,
    val directCidrIpv6Path: String,
    val enableIpv6: Boolean,
    val enableRootIpv6Disabler: Boolean,
    val enableLocalDns: Boolean,
    val enableFakeDns: Boolean,
    val enableBoot: Boolean,
) {
    val configPath: String
        get() = runtimePaths.coreConfigPath
}

internal data class RootModeStartConfig(
    val root: RootStartConfig,
    val localProxyOptions: LocalProxyOptions,
    val asteriskdConfig: AsteriskdConfig,
)
