// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root

import kotlinx.serialization.Serializable

@Serializable
internal enum class AsteriskdMode(
    val configValue: String,
) {
    Tproxy("tproxy"),
    Tun("tun"),
    Tun2Socks("tun2socks"),
    Bpf2Socks("bpf2socks"),
}

@Serializable
internal data class AsteriskdConfig(
    val version: Int = 3,
    val mode: String,
    val enableIpv6: Boolean,
    val disableSystemIpv6: Boolean,
    val readyPath: String,
    val ignoredInterfaces: List<String>,
    val virtualInterfaces: List<String>,
    val hotspotInterfacePrefixes: List<String>,
    val ipv4Bypass: AsteriskdBypassTarget?,
    val ipv6Bypass: AsteriskdBypassTarget?,
    val bpfLocalMaps: AsteriskdBpfLocalMaps?,
    val bpf2socksTc: AsteriskdBpf2SocksTc?,
    val stopScriptPath: String,
    val statePath: String,
    val emergencyProcesses: List<AsteriskdEmergencyProcess>,
) {
    companion object {
        fun forMode(
            mode: AsteriskdMode,
            enableIpv6: Boolean,
            disableSystemIpv6: Boolean,
            readyPath: String,
            ignoredInterfaces: List<String>,
            virtualInterfaces: List<String>,
            hotspotInterfacePrefixes: List<String>,
            bypassConsumerChains: AsteriskdBypassConsumerChains?,
            stopScriptPath: String,
            statePath: String,
            emergencyProcesses: List<AsteriskdEmergencyProcess>,
        ): AsteriskdConfig {
            val ipv4Bypass = bypassConsumerChains?.let { consumers ->
                AsteriskdBypassTarget(
                    beginChain = AsteriskdIpv4BypassBeginChain,
                    endChain = AsteriskdIpv4BypassEndChain,
                    consumerChains = consumers.ipv4.distinct(),
                )
            }
            return AsteriskdConfig(
                mode = mode.configValue,
                enableIpv6 = enableIpv6,
                disableSystemIpv6 = disableSystemIpv6,
                readyPath = readyPath,
                ignoredInterfaces = ignoredInterfaces.distinct(),
                virtualInterfaces = virtualInterfaces.distinct(),
                hotspotInterfacePrefixes = hotspotInterfacePrefixes.distinct(),
                ipv4Bypass = ipv4Bypass,
                ipv6Bypass = bypassConsumerChains?.takeIf { enableIpv6 }?.let { consumers ->
                    AsteriskdBypassTarget(
                        beginChain = AsteriskdIpv6BypassBeginChain,
                        endChain = AsteriskdIpv6BypassEndChain,
                        consumerChains = consumers.ipv6.distinct(),
                    )
                },
                bpfLocalMaps = if (mode == AsteriskdMode.Bpf2Socks) {
                    AsteriskdBpfLocalMaps(
                        ipv4Path = "$RootBpf2SocksPinnedObjectDir/local_addr_v4",
                        ipv6Path = "$RootBpf2SocksPinnedObjectDir/local_addr_v6",
                    )
                } else {
                    null
                },
                bpf2socksTc = if (mode == AsteriskdMode.Bpf2Socks) {
                    AsteriskdBpf2SocksTc(
                        ingressPath = "$RootBpf2SocksPinnedObjectDir/tc_ingress",
                        egressPath = "$RootBpf2SocksPinnedObjectDir/tc_egress",
                        statePath = "$statePath.route-localnet",
                        preference = RootBpf2SocksTcPreference,
                        handle = RootBpf2SocksTcHandle,
                    )
                } else {
                    null
                },
                stopScriptPath = stopScriptPath,
                statePath = statePath,
                emergencyProcesses = emergencyProcesses,
            )
        }
    }
}

@Serializable
internal data class AsteriskdBypassTarget(
    val beginChain: String,
    val endChain: String,
    val consumerChains: List<String>,
)

internal data class AsteriskdBypassConsumerChains(
    val ipv4: List<String>,
    val ipv6: List<String>,
)

@Serializable
internal data class AsteriskdBpfLocalMaps(
    val ipv4Path: String,
    val ipv6Path: String?,
)

@Serializable
internal data class AsteriskdBpf2SocksTc(
    val ingressPath: String,
    val egressPath: String,
    val statePath: String,
    val preference: Int,
    val handle: Int,
)

@Serializable
internal data class AsteriskdEmergencyProcess(
    val pidPath: String,
    val commandMarker: String,
)

internal const val AsteriskdIpv4BypassBeginChain = RootAsteriskdBypass4Begin
internal const val AsteriskdIpv4BypassEndChain = RootAsteriskdBypass4End
internal const val AsteriskdIpv6BypassBeginChain = RootAsteriskdBypass6Begin
internal const val AsteriskdIpv6BypassEndChain = RootAsteriskdBypass6End
