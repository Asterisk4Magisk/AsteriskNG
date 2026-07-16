// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.stats

import engine.xray.XrayTags
import java.util.Locale

internal enum class XrayTrafficDirection {
    Uplink,
    Downlink,
}

internal data class XrayTrafficStat(
    val tag: String,
    val direction: XrayTrafficDirection,
    val bytes: Long,
)

internal data class XrayTrafficBytes(
    val uplink: Long = 0L,
    val downlink: Long = 0L,
) {
    operator fun plus(other: XrayTrafficBytes): XrayTrafficBytes {
        return XrayTrafficBytes(
            uplink = uplink + other.uplink,
            downlink = downlink + other.downlink,
        )
    }
}

internal data class XrayTrafficSessionSample(
    val speedBytesPerSecond: XrayTrafficBytes,
    val totalBytes: XrayTrafficBytes,
)

internal class XrayTrafficSessionAccumulator {
    private var totalBytes = XrayTrafficBytes()

    fun record(
        delta: XrayTrafficBytes,
        elapsedMillis: Long,
    ): XrayTrafficSessionSample {
        val elapsedSeconds = elapsedMillis.coerceAtLeast(1L).toDouble() / 1000.0
        totalBytes += delta
        return XrayTrafficSessionSample(
            speedBytesPerSecond = XrayTrafficBytes(
                uplink = (delta.uplink / elapsedSeconds).toLong(),
                downlink = (delta.downlink / elapsedSeconds).toLong(),
            ),
            totalBytes = totalBytes,
        )
    }

}

internal fun parseXrayTrafficStat(
    name: String,
    bytes: Long,
): XrayTrafficStat? {
    val parts = name.split(XrayStatNameSeparator)
    if (parts.size != XrayOutboundTrafficStatPartCount) return null
    if (parts[0] != XrayOutboundStatPrefix || parts[2] != XrayTrafficStatMiddle) return null
    val direction = when (parts[3]) {
        XrayTrafficStatUplink -> XrayTrafficDirection.Uplink
        XrayTrafficStatDownlink -> XrayTrafficDirection.Downlink
        else -> return null
    }
    return XrayTrafficStat(
        tag = parts[1],
        direction = direction,
        bytes = bytes,
    )
}

internal fun aggregateProxyTraffic(stats: List<XrayTrafficStat>): XrayTrafficBytes {
    var uplink = 0L
    var downlink = 0L
    stats
        .asSequence()
        .filter { stat -> stat.tag !in ExcludedProxyTrafficOutboundTags }
        .forEach { stat ->
            when (stat.direction) {
                XrayTrafficDirection.Uplink -> uplink += stat.bytes
                XrayTrafficDirection.Downlink -> downlink += stat.bytes
            }
        }
    return XrayTrafficBytes(uplink = uplink, downlink = downlink)
}

internal fun Long.toTrafficSizeString(): String {
    var size = toDouble()
    var unitIndex = 0
    while (size >= TrafficUnitThreshold && unitIndex < TrafficUnits.lastIndex) {
        size /= TrafficUnitDivisor
        unitIndex += 1
    }
    return String.format(Locale.getDefault(), "%.1f %s", size, TrafficUnits[unitIndex])
}

internal fun Long.toTrafficSpeedString(): String {
    return "${toTrafficSizeString()}/s"
}

private const val XrayStatNameSeparator = ">>>"
private const val XrayOutboundTrafficStatPartCount = 4
private const val XrayOutboundStatPrefix = "outbound"
private const val XrayTrafficStatMiddle = "traffic"
private const val XrayTrafficStatUplink = "uplink"
private const val XrayTrafficStatDownlink = "downlink"
private const val TrafficUnitThreshold = 1000L
private const val TrafficUnitDivisor = 1024.0

private val TrafficUnits = listOf("B", "KB", "MB", "GB", "TB", "PB")
private val ExcludedProxyTrafficOutboundTags = setOf(
    "direct",
    "block",
    "dns-out",
    "fragment",
    "api",
    XrayTags.DEFAULT_ROUTE_LOOPBACK,
)
