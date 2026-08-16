// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.servicecontrol

import app.ServiceControlSettings
import app.ServiceControlWifiRule
import app.ServiceControlWifiRuleKind
import java.nio.charset.StandardCharsets

private const val MaxServiceControlListSize = 64

sealed interface ServiceCronParseResult {
    data class Valid(val expression: String) : ServiceCronParseResult

    data class Invalid(val reason: ServiceCronError) : ServiceCronParseResult
}

enum class ServiceCronError {
    FieldCount,
    EmptyTerm,
    InvalidSyntax,
    OutOfRange,
    InvalidStep,
}

fun parseServiceCron(value: String): ServiceCronParseResult {
    val expression = value.trim()
    if (expression.isEmpty()) return ServiceCronParseResult.Invalid(ServiceCronError.FieldCount)
    val fields = expression.split(Regex("\\s+"))
    if (fields.size != CronField.entries.size) {
        return ServiceCronParseResult.Invalid(ServiceCronError.FieldCount)
    }
    fields.zip(CronField.entries).forEach { (field, definition) ->
        parseCronField(field, definition)?.let { return ServiceCronParseResult.Invalid(it) }
    }
    return ServiceCronParseResult.Valid(expression)
}

private enum class CronField(val minimum: Int, val maximum: Int) {
    Minute(0, 59),
    Hour(0, 23),
    DayOfMonth(1, 31),
    Month(1, 12),
    DayOfWeek(0, 7),
}

private fun parseCronField(value: String, field: CronField): ServiceCronError? {
    if (value.isEmpty()) return ServiceCronError.InvalidSyntax
    val terms = value.split(',')
    if (terms.any(String::isEmpty)) return ServiceCronError.EmptyTerm
    for (term in terms) {
        val stepParts = term.split('/')
        if (stepParts.size > 2 || stepParts[0].isEmpty()) return ServiceCronError.InvalidSyntax
        if (stepParts.size == 2) {
            val step = stepParts[1].toAsciiIntOrNull() ?: return ServiceCronError.InvalidStep
            if (step <= 0) return ServiceCronError.InvalidStep
        }
        val base = stepParts[0]
        if (base == "*") continue
        val rangeParts = base.split('-')
        if (rangeParts.size > 2 || rangeParts.any(String::isEmpty)) {
            return ServiceCronError.InvalidSyntax
        }
        val start = rangeParts[0].toAsciiIntOrNull() ?: return ServiceCronError.InvalidSyntax
        val end = if (rangeParts.size == 2) {
            rangeParts[1].toAsciiIntOrNull() ?: return ServiceCronError.InvalidSyntax
        } else {
            start
        }
        if (start !in field.minimum..field.maximum || end !in field.minimum..field.maximum) {
            return ServiceCronError.OutOfRange
        }
        if (start > end) return ServiceCronError.OutOfRange
    }
    return null
}

private fun String.toAsciiIntOrNull(): Int? =
    takeIf { value -> value.isNotEmpty() && value.all { character -> character in '0'..'9' } }
        ?.toIntOrNull()

fun normalizeBssidOrNull(value: String): String? {
    val compact = value.filterNot { character -> character == ':' || character == '-' }
    if (compact.length != 12 || compact.any { character -> character !in '0'..'9' && character.lowercaseChar() !in 'a'..'f' }) {
        return null
    }
    return compact.lowercase().chunked(2).joinToString(":")
}

fun isValidServiceSsid(value: String): Boolean {
    val size = value.toByteArray(StandardCharsets.UTF_8).size
    return size in 1..32
}

fun canSaveServiceControlDraft(
    settings: ServiceControlSettings,
    hasPendingEditor: Boolean,
): Boolean {
    if (!settings.enabled) return true
    if (settings.schedule.enabled &&
        (parseServiceCron(settings.schedule.startCron) is ServiceCronParseResult.Invalid ||
            parseServiceCron(settings.schedule.stopCron) is ServiceCronParseResult.Invalid)
    ) {
        return false
    }
    if (!settings.wifi.enabled) return true
    if (hasPendingEditor) return false
    return listOf(
        settings.wifi.connectStart,
        settings.wifi.connectStop,
        settings.wifi.disconnectStart,
        settings.wifi.disconnectStop,
    ).filter(ServiceControlWifiRule::enabled).all(ServiceControlWifiRule::isValidForSave)
}

private fun ServiceControlWifiRule.isValidForSave(): Boolean =
    ssids.size <= MaxServiceControlListSize &&
        bssids.size <= MaxServiceControlListSize &&
        ssids.all(::isValidServiceSsid) &&
        bssids.all { value -> normalizeBssidOrNull(value) != null }

fun normalizeServiceControlSettings(value: ServiceControlSettings): ServiceControlSettings =
    value.copy(
        schedule = value.schedule.copy(
            startCron = value.schedule.startCron.trim(),
            stopCron = value.schedule.stopCron.trim(),
        ),
        wifi = value.wifi.copy(
            connectStart = normalizeWifiRule(value.wifi.connectStart),
            connectStop = normalizeWifiRule(value.wifi.connectStop),
            disconnectStart = normalizeWifiRule(value.wifi.disconnectStart),
            disconnectStop = normalizeWifiRule(value.wifi.disconnectStop),
        ),
    ).enforceWifiRuleExclusion()

private fun normalizeWifiRule(value: ServiceControlWifiRule): ServiceControlWifiRule =
    value.copy(
        ssids = value.ssids.asSequence()
            .filter(::isValidServiceSsid)
            .distinct()
            .take(MaxServiceControlListSize)
            .toList(),
        bssids = value.bssids.asSequence()
            .mapNotNull(::normalizeBssidOrNull)
            .distinct()
            .take(MaxServiceControlListSize)
            .toList(),
    )

fun setWifiRuleEnabled(
    settings: ServiceControlSettings,
    rule: ServiceControlWifiRuleKind,
    enabled: Boolean,
): ServiceControlSettings {
    val wifi = settings.wifi
    val changed = when (rule) {
        ServiceControlWifiRuleKind.ConnectStart -> wifi.copy(
            connectStart = wifi.connectStart.copy(enabled = enabled),
            connectStop = if (enabled) wifi.connectStop.copy(enabled = false) else wifi.connectStop,
        )
        ServiceControlWifiRuleKind.ConnectStop -> wifi.copy(
            connectStart = if (enabled) wifi.connectStart.copy(enabled = false) else wifi.connectStart,
            connectStop = wifi.connectStop.copy(enabled = enabled),
        )
        ServiceControlWifiRuleKind.DisconnectStart -> wifi.copy(
            disconnectStart = wifi.disconnectStart.copy(enabled = enabled),
            disconnectStop = if (enabled) wifi.disconnectStop.copy(enabled = false) else wifi.disconnectStop,
        )
        ServiceControlWifiRuleKind.DisconnectStop -> wifi.copy(
            disconnectStart = if (enabled) wifi.disconnectStart.copy(enabled = false) else wifi.disconnectStart,
            disconnectStop = wifi.disconnectStop.copy(enabled = enabled),
        )
    }
    return settings.copy(wifi = changed)
}

private fun ServiceControlSettings.enforceWifiRuleExclusion(): ServiceControlSettings {
    var result = this
    if (wifi.connectStart.enabled && wifi.connectStop.enabled) {
        result = setWifiRuleEnabled(result, ServiceControlWifiRuleKind.ConnectStop, true)
    }
    if (result.wifi.disconnectStart.enabled && result.wifi.disconnectStop.enabled) {
        result = setWifiRuleEnabled(result, ServiceControlWifiRuleKind.DisconnectStop, true)
    }
    return result
}
