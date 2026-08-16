// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app

data class ServiceControlSettings(
    val enabled: Boolean = false,
    val schedule: ServiceControlSchedule = ServiceControlSchedule(),
    val wifi: ServiceControlWifi = ServiceControlWifi(),
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
