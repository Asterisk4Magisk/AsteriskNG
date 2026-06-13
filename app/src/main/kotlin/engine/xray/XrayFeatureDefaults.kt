// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.xray

const val DefaultMuxConcurrency = "8"
const val DefaultMuxXudpConcurrency = "16"
const val DefaultMuxUdp443Mode = 0
const val MaxMuxConcurrency = 128
const val MaxMuxXudpConcurrency = 1024
val MuxUdp443Values = listOf("reject", "allow", "skip")

const val DefaultFragmentPackets = "tlshello"
const val DefaultFragmentLength = "100-200"
const val DefaultFragmentInterval = "10-20"
const val MaxFragmentInputLength = 21
val FragmentPacketsValues = listOf("tlshello", "1-2", "1-3", "1-5")

const val XrayFakeDnsIpv4Pool = "198.18.0.0/15"
const val XrayFakeDnsIpv4OnlyPoolSize = 65_535
const val XrayLogDisabled = "none"
val DefaultDirectDnsDomains = listOf("geosite:cn")
