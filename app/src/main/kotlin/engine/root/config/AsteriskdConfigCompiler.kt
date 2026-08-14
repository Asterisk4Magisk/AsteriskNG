// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.config

import app.modes.ProxyAppListModeBlacklist
import app.modes.ProxyAppListModeGlobal
import app.modes.ProxyAppListModeWhitelist
import engine.root.daemon.config.AsteriskdAppPolicy
import engine.root.daemon.config.AsteriskdAppPolicyMode
import engine.root.daemon.config.AsteriskdConfig
import engine.root.daemon.config.AsteriskdCoreConfig
import engine.root.daemon.config.AsteriskdCoreType
import engine.root.daemon.config.AsteriskdHelper
import engine.root.daemon.config.AsteriskdMatcher
import engine.root.daemon.config.AsteriskdMode
import engine.root.daemon.config.AsteriskdModeOptions
import engine.root.daemon.config.AsteriskdNetworkConfig
import engine.root.daemon.config.AsteriskdNgConfigFactory
import engine.root.daemon.config.AsteriskdOwner

private val RootStartConfig.disableSystemIpv6: Boolean
    get() = !enableIpv6 && enableRootIpv6Disabler

internal fun RootStartConfig.buildAsteriskdConfig(
    mode: AsteriskdMode,
    iptablesConfig: RootIptablesConfig,
    virtualInterfaces: List<String>,
    modeOptions: AsteriskdModeOptions,
    helper: AsteriskdHelper? = null,
): AsteriskdConfig {
    AsteriskdNgConfigFactory.requireRunnableMode(mode)
    val matcher = if (iptablesConfig.enableEbpfRules && mode != AsteriskdMode.Bpf2Socks) {
        AsteriskdMatcher(runtimePaths.matcherExecutablePath)
    } else {
        null
    }
    val useDirectCidrs =
        iptablesConfig.enableEbpfDirectCidrBypass && (matcher != null || mode == AsteriskdMode.Bpf2Socks)
    return AsteriskdConfig(
        owner = AsteriskdOwner.AsteriskNg,
        coreType = AsteriskdCoreType.Xray,
        coreExecutablePath = runtimePaths.coreExecutablePath,
        coreConfigPath = runtimePaths.coreConfigPath,
        statePath = runtimePaths.statePath,
        logPath = runtimePaths.logPath,
        mode = mode,
        core = AsteriskdCoreConfig(
            workingDirectory = runtimePaths.workingDirectory,
            readinessTimeoutMilliseconds = 5000,
            ageSecretKey = null,
        ),
        network = AsteriskdNetworkConfig(
            enableIpv6 = enableIpv6,
            disableSystemIpv6 = disableSystemIpv6,
            enableLocalDns = enableLocalDns,
            enableFakeDns = enableFakeDns,
            fakeDnsIpv4Pool = "198.18.0.0/15".takeIf { enableFakeDns },
            ignoredInterfaces = iptablesConfig.ignoredInterfaces.distinct(),
            virtualInterfaces = virtualInterfaces.distinct(),
            hotspotInterfacePrefixes = iptablesConfig.externalInterfacePrefixes.distinct(),
            proxyPrivateCidrs = (
                iptablesConfig.proxyPrivateIpv4Cidrs +
                    iptablesConfig.proxyPrivateIpv6Cidrs.takeIf { enableIpv6 }.orEmpty()
                ).distinct(),
            bypassPrivateCidrs = (
                iptablesConfig.bypassPrivateIpv4Cidrs +
                    iptablesConfig.bypassPrivateIpv6Cidrs.takeIf { enableIpv6 }.orEmpty()
                ).distinct(),
            appPolicy = AsteriskdAppPolicy(
                mode = when (iptablesConfig.proxyAppListMode) {
                    ProxyAppListModeBlacklist -> AsteriskdAppPolicyMode.Blacklist
                    ProxyAppListModeWhitelist -> AsteriskdAppPolicyMode.Whitelist
                    ProxyAppListModeGlobal -> AsteriskdAppPolicyMode.Global
                    else -> error("Unsupported application policy mode")
                },
                uids = iptablesConfig.proxyApplicationUids.distinct().sorted()
                    .takeUnless { iptablesConfig.proxyAppListMode == ProxyAppListModeGlobal }
                    .orEmpty(),
                bypassUids = iptablesConfig.forcedBypassUids.distinct().sorted(),
                directCidrPathV4 = directCidrIpv4Path.takeIf { useDirectCidrs },
                directCidrPathV6 = directCidrIpv6Path.takeIf { useDirectCidrs },
            ),
        ),
        modeOptions = modeOptions,
        matcher = matcher,
        helper = helper,
    )
}
