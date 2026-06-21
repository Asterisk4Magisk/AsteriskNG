// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package data.backup

import app.AppState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal const val AppBackupFormat = "asteriskng-backup"
internal const val CurrentAppBackupVersion = 1

private val BackupDefaults = AppState()

@Serializable
internal data class AppBackupFile(
    val format: String = "",
    val version: Int = 0,
    val createdAtMillis: Long = 0L,
    val appVersionName: String = "",
    val appVersionCode: Int = 0,
    val data: AppBackupData = AppBackupData(),
)

@Serializable
internal data class AppBackupData(
    val settings: AppBackupSettings = AppBackupSettings(),
    val subscriptionGroups: List<AppBackupSubscriptionGroup> = emptyList(),
    val proxyServers: List<AppBackupProxyServer> = emptyList(),
    val routeRules: List<AppBackupRouteRule> = emptyList(),
    val proxyAppListSelectedApps: List<String> = emptyList(),
)

@Serializable
internal data class AppBackupSettings(
    val colorMode: Int = BackupDefaults.colorMode,
    val languageMode: Int = BackupDefaults.languageMode,
    val seedIndex: Int = BackupDefaults.seedIndex,
    val enableAllProxyGroup: Boolean = BackupDefaults.enableAllProxyGroup,
    val enableResolveProxyServerDomain: Boolean = BackupDefaults.enableResolveProxyServerDomain,
    val enableVpnLocalDns: Boolean = BackupDefaults.enableVpnLocalDns,
    val localProxyPort: String = BackupDefaults.localProxyPort,
    val enableDynamicLocalProxyPort: Boolean = BackupDefaults.enableDynamicLocalProxyPort,
    val localProxyListenAllInterfaces: Boolean = BackupDefaults.localProxyListenAllInterfaces,
    val localProxyUsername: String = BackupDefaults.localProxyUsername,
    val localProxyPassword: String = BackupDefaults.localProxyPassword,
    val enableVpnAppendHttpProxy: Boolean = BackupDefaults.enableVpnAppendHttpProxy,
    val tunMtu: String = BackupDefaults.tunMtu,
    val tunVpnDns: String = BackupDefaults.tunVpnDns,
    val tunIpv4Cidr: String = BackupDefaults.tunIpv4Cidr,
    val tunIpv6Cidr: String = BackupDefaults.tunIpv6Cidr,
    val selectedProxyServerId: Int = BackupDefaults.selectedProxyServerId,
    val proxyServerListLayout: Int = BackupDefaults.proxyServerListLayout,
    val proxyServerListSort: Int = BackupDefaults.proxyServerListSort,
    val routeDomainStrategy: Int = BackupDefaults.routeDomainStrategy,
    val defaultRouteOutboundTag: String = BackupDefaults.defaultRouteOutboundTag,
    val coreLogLevel: Int = BackupDefaults.coreLogLevel,
    val enableAccessLog: Boolean = BackupDefaults.enableAccessLog,
    val resourceFileSource: Int = BackupDefaults.resourceFileSource,
    val customResourceFileGeoIpUrl: String = BackupDefaults.customResourceFileGeoIpUrl,
    val customResourceFileGeoSiteUrl: String = BackupDefaults.customResourceFileGeoSiteUrl,
    val customResourceFileGeoIpOnlyCnPrivateUrl: String = BackupDefaults.customResourceFileGeoIpOnlyCnPrivateUrl,
    val customResourceFiles: List<AppBackupCustomResourceFile> = emptyList(),
    val enableSniffing: Boolean = BackupDefaults.enableSniffing,
    val enableSniffingRouteOnly: Boolean = BackupDefaults.enableSniffingRouteOnly,
    val enableMux: Boolean = BackupDefaults.enableMux,
    val muxConcurrency: String = BackupDefaults.muxConcurrency,
    val muxXudpConcurrency: String = BackupDefaults.muxXudpConcurrency,
    val muxXudpProxyUdp443: Int = BackupDefaults.muxXudpProxyUdp443,
    val enableFragment: Boolean = BackupDefaults.enableFragment,
    val fragmentPackets: String = BackupDefaults.fragmentPackets,
    val fragmentLength: String = BackupDefaults.fragmentLength,
    val fragmentInterval: String = BackupDefaults.fragmentInterval,
    val enableIpv6: Boolean = BackupDefaults.enableIpv6,
    val enableIpv6Prefer: Boolean = BackupDefaults.enableIpv6Prefer,
    val enableFakeDns: Boolean = BackupDefaults.enableFakeDns,
    val proxyDns: List<String> = BackupDefaults.proxyDns,
    val directDns: List<String> = BackupDefaults.directDns,
    val directDnsDomains: List<String> = BackupDefaults.directDnsDomains,
    val enableDirectDnsForProxyServerDomains: Boolean = BackupDefaults.enableDirectDnsForProxyServerDomains,
    val dnsHosts: List<String> = BackupDefaults.dnsHosts,
    val transparentProxyPort: String = BackupDefaults.transparentProxyPort,
    val enableRootIpv6Disabler: Boolean = BackupDefaults.enableRootIpv6Disabler,
    val socks5ProxyPort: String = BackupDefaults.socks5ProxyPort,
    val enableHttpProxy: Boolean = BackupDefaults.enableHttpProxy,
    val httpProxyPort: String = BackupDefaults.httpProxyPort,
    val externalInterfaces: List<String> = BackupDefaults.externalInterfaces,
    val ignoredInterfaces: List<String> = BackupDefaults.ignoredInterfaces,
    val privateAddressCidrs: List<String> = BackupDefaults.privateAddressCidrs,
    val proxyAppListMode: Int = BackupDefaults.proxyAppListMode,
)

@Serializable
internal data class AppBackupCustomResourceFile(
    val id: Int = 0,
    val name: String = "",
    val url: String = "",
)

@Serializable
internal data class AppBackupSubscriptionGroup(
    val id: Int = 0,
    val name: String = "",
    val url: String = "",
    val userAgent: String = "",
    val updateInterval: String = "",
    val updateViaProxy: Boolean = false,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    val lastUpdatedAtMillis: Long = 0L,
)

@Serializable
internal data class AppBackupProxyServer(
    val id: Int = 0,
    val groupId: Int = 0,
    val protocol: String = "",
    val payload: JsonElement = JsonObject(emptyMap()),
)

@Serializable
internal data class AppBackupRouteRule(
    val id: Int = 0,
    val remarks: String = "",
    val outboundTag: String = BackupDefaults.defaultRouteOutboundTag,
    val domain: List<String> = emptyList(),
    val ip: List<String> = emptyList(),
    val process: List<String> = emptyList(),
    val port: String = "",
    val protocol: String = "",
    val network: String = "",
    val enabled: Boolean = true,
)

internal data class AppBackupRestorePreview(
    val backup: AppBackupFile,
    val restoredState: AppState,
    val warnings: List<AppBackupWarning>,
) {
    val subscriptionGroupCount: Int
        get() = restoredState.subscriptionGroups.size

    val proxyServerCount: Int
        get() = restoredState.proxyServers.size

    val routeRuleCount: Int
        get() = restoredState.routeRules.size
}

internal sealed interface AppBackupWarning {
    data class MissingChainProxyMembers(
        val count: Int,
    ) : AppBackupWarning
}
