// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.daemon.config

import app.ServiceControlSettings
import features.settings.servicecontrol.normalizeServiceControlSettings

internal enum class AsteriskdOwner(val wireValue: String) {
    AsteriskNg("asteriskng"),
    AsteriskBox("asteriskbox"),
    AsteriskMeta("asteriskmeta"),
}

internal enum class AsteriskdCoreType(val wireValue: String) {
    Xray("xray"),
    SingBox("sing-box"),
    Mihomo("mihomo"),
}

internal enum class AsteriskdMode(val wireValue: String) {
    Tproxy("tproxy"),
    Tun("tun"),
    Tun2Socks("tun2socks"),
    Bpf2Socks("bpf2socks"),
    Ebpf("ebpf"),
    ;

    companion object {
        fun fromWire(value: String): AsteriskdMode = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown asteriskd mode")
    }
}

internal enum class AsteriskdAppPolicyMode(val wireValue: String) {
    Global("global"),
    Blacklist("blacklist"),
    Whitelist("whitelist"),
}

internal data class AsteriskdConfig(
    val owner: AsteriskdOwner,
    val coreType: AsteriskdCoreType,
    val coreExecutablePath: String,
    val coreConfigPath: String,
    val statePath: String,
    val logPath: String,
    val mode: AsteriskdMode,
    val core: AsteriskdCoreConfig,
    val network: AsteriskdNetworkConfig,
    val modeOptions: AsteriskdModeOptions,
    val matcher: AsteriskdMatcher?,
    val helper: AsteriskdHelper?,
    val serviceControl: AsteriskdServiceControlConfig,
)

internal data class AsteriskdServiceControlConfig(
    val enabled: Boolean,
    val schedule: AsteriskdScheduleControl,
    val wifi: AsteriskdWifiControl,
)

internal data class AsteriskdScheduleControl(
    val enabled: Boolean,
    val startCron: String,
    val stopCron: String,
)

internal data class AsteriskdWifiControl(
    val enabled: Boolean,
    val connectStart: AsteriskdWifiRule,
    val connectStop: AsteriskdWifiRule,
    val disconnectStart: AsteriskdWifiRule,
    val disconnectStop: AsteriskdWifiRule,
)

internal data class AsteriskdWifiRule(
    val enabled: Boolean,
    val ssids: List<String>,
    val bssids: List<String>,
)

internal fun ServiceControlSettings.toAsteriskdServiceControlConfig(): AsteriskdServiceControlConfig {
    val value = normalizeServiceControlSettings(this)
    return AsteriskdServiceControlConfig(
        enabled = value.enabled,
        schedule = AsteriskdScheduleControl(
            enabled = value.schedule.enabled,
            startCron = value.schedule.startCron,
            stopCron = value.schedule.stopCron,
        ),
        wifi = AsteriskdWifiControl(
            enabled = value.wifi.enabled,
            connectStart = value.wifi.connectStart.toAsteriskdWifiRule(),
            connectStop = value.wifi.connectStop.toAsteriskdWifiRule(),
            disconnectStart = value.wifi.disconnectStart.toAsteriskdWifiRule(),
            disconnectStop = value.wifi.disconnectStop.toAsteriskdWifiRule(),
        ),
    )
}

private fun app.ServiceControlWifiRule.toAsteriskdWifiRule(): AsteriskdWifiRule =
    AsteriskdWifiRule(enabled = enabled, ssids = ssids, bssids = bssids)

internal data class AsteriskdCoreConfig(
    val workingDirectory: String,
    val readinessTimeoutMilliseconds: Int,
    val ageSecretKey: String?,
)

internal data class AsteriskdNetworkConfig(
    val enableIpv6: Boolean,
    val disableSystemIpv6: Boolean,
    val enableLocalDns: Boolean,
    val enableFakeDns: Boolean,
    val fakeDnsIpv4Pool: String?,
    val ignoredInterfaces: List<String>,
    val virtualInterfaces: List<String>,
    val hotspotInterfacePrefixes: List<String>,
    val proxyPrivateCidrs: List<String>,
    val bypassPrivateCidrs: List<String>,
    val appPolicy: AsteriskdAppPolicy,
)

internal data class AsteriskdAppPolicy(
    val mode: AsteriskdAppPolicyMode,
    val uids: List<Int>,
    val bypassUids: List<Int>,
    val directCidrPathV4: String?,
    val directCidrPathV6: String?,
)

internal data class AsteriskdModeOptions(
    val transparentPort: Int?,
    val tunnelName: String?,
)

internal data class AsteriskdMatcher(
    val executablePath: String,
)

internal sealed interface AsteriskdHelper

internal data class AsteriskdHevSocks5TunnelHelper(
    val executablePath: String,
    val socksHost: String,
    val socksPort: Int,
    val tunnelName: String,
    val mtu: Int,
    val ipv4Address: String,
    val ipv6Address: String?,
    val multiQueue: Boolean,
    val tcpFastOpen: Boolean,
    val tcpReadWriteTimeoutMilliseconds: Int = 300000,
    val udpReadWriteTimeoutMilliseconds: Int = 60000,
) : AsteriskdHelper

internal data class AsteriskdBpf2SocksHelper(
    val executablePath: String,
    val bridgeListenAddress: String,
    val bridgePort: Int,
    val socksHost: String,
    val socksPort: Int,
    val workerCount: Int = 0,
    val tcpBufferSize: Int = 65536,
    val maxTcpSessions: Int = 4096,
    val tcpConnectTimeoutMilliseconds: Int = 10000,
    val tcpIdleTimeoutMilliseconds: Int = 300000,
    val udpSocketBufferSize: Int = 524288,
    val udpBatchSize: Int = 32,
    val maxUdpSessions: Int = 4096,
    val maxUdpBindings: Int = 16384,
    val udpIdleTimeoutSeconds: Int = 60,
    val maxUdpPendingBytes: Int = 64 * 1024 * 1024,
    val dnsTransactionTimeoutMilliseconds: Int = 60000,
) : AsteriskdHelper
