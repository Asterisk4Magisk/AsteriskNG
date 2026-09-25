// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app

data class ServiceControlSettings(
    val enabled: Boolean = false,
    val schedule: ServiceControlSchedule = ServiceControlSchedule(),
    val wifi: ServiceControlWifi = ServiceControlWifi(),
    val keyguard: ServiceControlKeyguard = ServiceControlKeyguard(),
)

data class ServiceControlSchedule(
    val enabled: Boolean = false,
    val startCron: String = "",
    val stopCron: String = "",
)

data class ServiceControlWifi(
    val enabled: Boolean = false,
    val connectStart: ServiceControlWifiRule = ServiceControlWifiRule(),
    val connectStop: ServiceControlWifiRule = ServiceControlWifiRule(),
    val disconnectStart: ServiceControlWifiRule = ServiceControlWifiRule(),
    val disconnectStop: ServiceControlWifiRule = ServiceControlWifiRule(),
)

data class ServiceControlWifiRule(
    val enabled: Boolean = false,
    val ssids: List<String> = emptyList(),
    val bssids: List<String> = emptyList(),
)

enum class ServiceControlWifiRuleKind {
    ConnectStart,
    ConnectStop,
    DisconnectStart,
    DisconnectStop,
}

data class ServiceControlKeyguard(
    val enabled: Boolean = false,
    val lockStart: Boolean = false,
    val lockStop: Boolean = false,
    val unlockStart: Boolean = false,
    val unlockStop: Boolean = false,
)

enum class ServiceControlKeyguardRule { LockStart, LockStop, UnlockStart, UnlockStop }

fun ServiceControlKeyguard.withRule(rule: ServiceControlKeyguardRule, enabled: Boolean): ServiceControlKeyguard =
    when (rule) {
        ServiceControlKeyguardRule.LockStart -> copy(lockStart = enabled, lockStop = lockStop && !enabled)
        ServiceControlKeyguardRule.LockStop -> copy(lockStop = enabled, lockStart = lockStart && !enabled)
        ServiceControlKeyguardRule.UnlockStart -> copy(unlockStart = enabled, unlockStop = unlockStop && !enabled)
        ServiceControlKeyguardRule.UnlockStop -> copy(unlockStop = enabled, unlockStart = unlockStart && !enabled)
    }

fun supportsKeyguardControl(sdk: Int): Boolean = sdk >= 33
