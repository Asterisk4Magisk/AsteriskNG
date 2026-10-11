// SPDX-License-Identifier: GPL-3.0
package engine.root.dns

import engine.root.daemon.config.AsteriskdDnsEnhancementConfig
import engine.root.daemon.config.AsteriskdMode
import java.nio.ByteBuffer
import java.security.MessageDigest

// Pure configuration planning: VPN never enters the ROOT compiler and core-managed
// modes produce no request. Capability is checked again by the listener-owning daemon.
internal fun rootDnsEnhancementConfig(
    requested: Boolean,
    mode: AsteriskdMode,
    enableLocalDns: Boolean,
    coreConfig: ByteArray,
): AsteriskdDnsEnhancementConfig? {
    if (!requested || !enableLocalDns || mode == AsteriskdMode.Tun || mode == AsteriskdMode.Ebpf) return null
    val digest = MessageDigest.getInstance("SHA-256").digest(coreConfig)
    val generation = (ByteBuffer.wrap(digest).long and Long.MAX_VALUE).coerceAtLeast(1L)
    return AsteriskdDnsEnhancementConfig(coreDnsGeneration = generation)
}
