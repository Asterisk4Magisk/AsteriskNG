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
    val version: Int = 2,
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
            stopScriptPath: String,
            statePath: String,
            emergencyProcesses: List<AsteriskdEmergencyProcess>,
        ): AsteriskdConfig {
            val bypass = if (mode == AsteriskdMode.Bpf2Socks) {
                null
            } else {
                AsteriskdBypassTarget(
                    anchorChain = AsteriskdIpv4BypassAnchorChain,
                    slotAChain = AsteriskdIpv4BypassSlotAChain,
                    slotBChain = AsteriskdIpv4BypassSlotBChain,
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
                ipv4Bypass = bypass,
                ipv6Bypass = bypass?.takeIf { enableIpv6 }?.copy(
                    anchorChain = AsteriskdIpv6BypassAnchorChain,
                    slotAChain = AsteriskdIpv6BypassSlotAChain,
                    slotBChain = AsteriskdIpv6BypassSlotBChain,
                ),
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
    val anchorChain: String,
    val slotAChain: String,
    val slotBChain: String,
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

internal const val AsteriskdIpv4BypassAnchorChain = RootAsteriskdBypass4Anchor
internal const val AsteriskdIpv4BypassSlotAChain = RootAsteriskdBypass4SlotA
internal const val AsteriskdIpv4BypassSlotBChain = RootAsteriskdBypass4SlotB
internal const val AsteriskdIpv6BypassAnchorChain = RootAsteriskdBypass6Anchor
internal const val AsteriskdIpv6BypassSlotAChain = RootAsteriskdBypass6SlotA
internal const val AsteriskdIpv6BypassSlotBChain = RootAsteriskdBypass6SlotB
