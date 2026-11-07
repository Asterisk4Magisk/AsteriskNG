// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package utils

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.round

internal enum class ReadableByteUnit(
    val label: String,
) {
    B("B"),
    KiB("KiB"),
    MiB("MiB"),
    GiB("GiB"),
    TiB("TiB"),
}

internal fun Long.toReadableBytes(
    maxUnit: ReadableByteUnit = ReadableByteUnit.TiB,
    keepTrailingZero: Boolean = false,
): String {
    val units = ReadableByteUnit.entries
    val maxUnitIndex = maxUnit.ordinal.coerceIn(0, units.lastIndex)
    var value = coerceAtLeast(0L).toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < maxUnitIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    val unit = units[unitIndex]
    return if (unit == ReadableByteUnit.B) {
        "${value.toLong()} ${unit.label}"
    } else {
        "${value.toOneDecimalText(keepTrailingZero)} ${unit.label}"
    }
}

internal fun Long.toReadableDateTimeOrDash(): String {
    if (this <= 0L) return "-"
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(this))
}

internal fun Long.toReadableDateOrDash(): String {
    if (this <= 0L) return "-"
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(this))
}

private fun Double.toOneDecimalText(keepTrailingZero: Boolean): String {
    val rounded = round(this * 10.0) / 10.0
    if (keepTrailingZero) {
        return String.format(Locale.US, "%.1f", rounded)
    }
    return rounded.toString().removeSuffix(".0")
}
