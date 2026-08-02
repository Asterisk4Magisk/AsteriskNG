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

private data class XrayTrafficSpeedSample(
    val bytes: XrayTrafficBytes,
    val elapsedMillis: Long,
)

internal class XrayTrafficSessionAccumulator {
    private var totalBytes = XrayTrafficBytes()
    private val speedSamples = ArrayDeque<XrayTrafficSpeedSample>()
    private var speedWindowElapsedMillis = 0L

    fun record(
        delta: XrayTrafficBytes,
        elapsedMillis: Long,
    ): XrayTrafficSessionSample {
        val sampleElapsedMillis = elapsedMillis.coerceAtLeast(1L)
        totalBytes += delta
        speedSamples.addLast(
            XrayTrafficSpeedSample(
                bytes = delta,
                elapsedMillis = sampleElapsedMillis,
            ),
        )
        speedWindowElapsedMillis += sampleElapsedMillis
        while (speedSamples.size > 1) {
            val oldest = speedSamples.first()
            if (speedWindowElapsedMillis - oldest.elapsedMillis < TrafficSpeedWindowMillis) break
            speedSamples.removeFirst()
            speedWindowElapsedMillis -= oldest.elapsedMillis
        }

        val speedWindowBytes = speedSamples.fold(XrayTrafficBytes()) { result, sample ->
            result + sample.bytes
        }
        val speedWindowSeconds = speedWindowElapsedMillis.toDouble() / 1000.0
        return XrayTrafficSessionSample(
            speedBytesPerSecond = XrayTrafficBytes(
                uplink = (speedWindowBytes.uplink / speedWindowSeconds).toLong(),
                downlink = (speedWindowBytes.downlink / speedWindowSeconds).toLong(),
            ),
            totalBytes = totalBytes,
        )
    }
}

internal fun parseXrayInboundTrafficStat(
    name: String,
    bytes: Long,
): XrayTrafficStat? {
    val parts = name.split(XrayStatNameSeparator)
    if (parts.size != XrayInboundTrafficStatPartCount) return null
    if (parts[0] != XrayInboundStatPrefix || parts[2] != XrayTrafficStatMiddle) return null
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

internal fun aggregateInboundTraffic(
    stats: List<XrayTrafficStat>,
    excludedInboundTags: Set<String>,
): XrayTrafficBytes {
    var uplink = 0L
    var downlink = 0L
    stats
        .asSequence()
        .filter { stat -> stat.tag !in excludedInboundTags }
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
private const val XrayInboundTrafficStatPartCount = 4
private const val XrayInboundStatPrefix = "inbound"
private const val XrayTrafficStatMiddle = "traffic"
private const val XrayTrafficStatUplink = "uplink"
private const val XrayTrafficStatDownlink = "downlink"
private const val TrafficSpeedWindowMillis = 3_000L
private const val TrafficUnitThreshold = 1000L
private const val TrafficUnitDivisor = 1024.0

private val TrafficUnits = listOf("B", "KB", "MB", "GB", "TB", "PB")
internal fun xrayTrafficExcludedInboundTags(apiTag: String): Set<String> = setOf(
    apiTag,
    XrayTags.DEFAULT_ROUTE_LOOPBACK_INBOUND,
)
