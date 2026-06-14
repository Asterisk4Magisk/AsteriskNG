// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package data

import android.content.Context
import android.content.SharedPreferences
import app.AppState
import app.CustomResourceFileState
import androidx.core.content.edit

internal class AppSettingsPreferences(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    fun load(): AppState {
        val defaults = AppState()
        val customResourceFiles = preferences.getCustomResourceFileList(
            KeyCustomResourceFiles,
            defaults.customResourceFiles,
        )
        val nextCustomResourceFileId = maxOf(
            preferences.getInt(KeyNextCustomResourceFileId, defaults.nextCustomResourceFileId),
            (customResourceFiles.maxOfOrNull { file -> file.id } ?: 0) + 1,
        )
        return defaults.copy(
            colorMode = preferences.getInt(KeyColorMode, defaults.colorMode),
            languageMode = preferences.getInt(KeyLanguageMode, defaults.languageMode),
            seedIndex = preferences.getInt(KeySeedIndex, defaults.seedIndex),
            nextSubscriptionGroupId = preferences.getInt(
                KeyNextSubscriptionGroupId,
                defaults.nextSubscriptionGroupId,
            ),
            enableAllProxyGroup = preferences.getBoolean(KeyEnableAllProxyGroup, defaults.enableAllProxyGroup),
            runMode = preferences.getInt(KeyRunMode, defaults.runMode),
            enableResolveProxyServerDomain = preferences.getBoolean(
                KeyEnableResolveProxyServerDomain,
                defaults.enableResolveProxyServerDomain,
            ),
            enableVpnLocalDns = preferences.getBoolean(KeyEnableVpnLocalDns, defaults.enableVpnLocalDns),
            localProxyPort = preferences.getString(KeyLocalProxyPort, defaults.localProxyPort) ?: defaults.localProxyPort,
            enableDynamicLocalProxyPort = preferences.getBoolean(
                KeyEnableDynamicLocalProxyPort,
                defaults.enableDynamicLocalProxyPort,
            ),
            localProxyListenAllInterfaces = preferences.getBoolean(
                KeyLocalProxyListenAllInterfaces,
                defaults.localProxyListenAllInterfaces,
            ),
            localProxyUsername = preferences.getString(
                KeyLocalProxyUsername,
                defaults.localProxyUsername,
            ) ?: defaults.localProxyUsername,
            localProxyPassword = preferences.getString(
                KeyLocalProxyPassword,
                defaults.localProxyPassword,
            ) ?: defaults.localProxyPassword,
            enableVpnAppendHttpProxy = preferences.getBoolean(
                KeyEnableVpnAppendHttpProxy,
                defaults.enableVpnAppendHttpProxy,
            ),
            tunMtu = preferences.getString(KeyTunMtu, defaults.tunMtu) ?: defaults.tunMtu,
            tunVpnDns = preferences.getString(KeyTunVpnDns, defaults.tunVpnDns) ?: defaults.tunVpnDns,
            tunIpv4Cidr = preferences.getString(KeyTunIpv4Cidr, defaults.tunIpv4Cidr) ?: defaults.tunIpv4Cidr,
            tunIpv6Cidr = preferences.getString(KeyTunIpv6Cidr, defaults.tunIpv6Cidr) ?: defaults.tunIpv6Cidr,
            nextProxyServerId = preferences.getInt(KeyNextProxyServerId, defaults.nextProxyServerId),
            selectedProxyServerId = preferences.getInt(KeySelectedProxyServerId, defaults.selectedProxyServerId),
            routeDomainStrategy = preferences.getInt(KeyRouteDomainStrategy, defaults.routeDomainStrategy),
            defaultRouteOutboundTag = preferences.getString(
                KeyDefaultRouteOutboundTag,
                defaults.defaultRouteOutboundTag,
            ) ?: defaults.defaultRouteOutboundTag,
            nextRouteRuleId = preferences.getInt(KeyNextRouteRuleId, defaults.nextRouteRuleId),
            coreLogLevel = preferences.getInt(KeyCoreLogLevel, defaults.coreLogLevel),
            enableAccessLog = preferences.getBoolean(KeyEnableAccessLog, defaults.enableAccessLog),
            resourceFileSource = preferences.getInt(KeyResourceFileSource, defaults.resourceFileSource),
            customResourceFileGeoIpUrl = preferences.getString(
                KeyCustomResourceFileGeoIpUrl,
                defaults.customResourceFileGeoIpUrl,
            ) ?: defaults.customResourceFileGeoIpUrl,
            customResourceFileGeoSiteUrl = preferences.getString(
                KeyCustomResourceFileGeoSiteUrl,
                defaults.customResourceFileGeoSiteUrl,
            ) ?: defaults.customResourceFileGeoSiteUrl,
            customResourceFileGeoIpOnlyCnPrivateUrl = preferences.getString(
                KeyCustomResourceFileGeoIpOnlyCnPrivateUrl,
                defaults.customResourceFileGeoIpOnlyCnPrivateUrl,
            ) ?: defaults.customResourceFileGeoIpOnlyCnPrivateUrl,
            customResourceFiles = customResourceFiles,
            nextCustomResourceFileId = nextCustomResourceFileId,
            enableSniffing = preferences.getBoolean(KeyEnableSniffing, defaults.enableSniffing),
            enableSniffingRouteOnly = preferences.getBoolean(
                KeyEnableSniffingRouteOnly,
                defaults.enableSniffingRouteOnly,
            ),
            enableMux = preferences.getBoolean(KeyEnableMux, defaults.enableMux),
            muxConcurrency = preferences.getString(KeyMuxConcurrency, defaults.muxConcurrency) ?: defaults.muxConcurrency,
            muxXudpConcurrency = preferences.getString(
                KeyMuxXudpConcurrency,
                defaults.muxXudpConcurrency,
            ) ?: defaults.muxXudpConcurrency,
            muxXudpProxyUdp443 = preferences.getInt(KeyMuxXudpProxyUdp443, defaults.muxXudpProxyUdp443),
            enableFragment = preferences.getBoolean(KeyEnableFragment, defaults.enableFragment),
            fragmentPackets = preferences.getString(
                KeyFragmentPackets,
                defaults.fragmentPackets,
            ) ?: defaults.fragmentPackets,
            fragmentLength = preferences.getString(
                KeyFragmentLength,
                defaults.fragmentLength,
            ) ?: defaults.fragmentLength,
            fragmentInterval = preferences.getString(
                KeyFragmentInterval,
                defaults.fragmentInterval,
            ) ?: defaults.fragmentInterval,
            enableIpv6 = preferences.getBoolean(KeyEnableIpv6, defaults.enableIpv6),
            enableIpv6Prefer = preferences.getBoolean(KeyEnableIpv6Prefer, defaults.enableIpv6Prefer),
            enableFakeDns = preferences.getBoolean(KeyEnableFakeDns, defaults.enableFakeDns),
            proxyDns = preferences.getStringList(KeyProxyDns, defaults.proxyDns),
            directDns = preferences.getStringList(KeyDirectDns, defaults.directDns),
            directDnsDomains = preferences.getStringList(KeyDirectDnsDomains, defaults.directDnsDomains),
            enableDirectDnsForProxyServerDomains = preferences.getBoolean(
                KeyEnableDirectDnsForProxyServerDomains,
                defaults.enableDirectDnsForProxyServerDomains,
            ),
            dnsHosts = preferences.getStringList(KeyDnsHosts, defaults.dnsHosts),
            transparentProxyPort = preferences.getString(
                KeyTransparentProxyPort,
                defaults.transparentProxyPort,
            ) ?: defaults.transparentProxyPort,
            enableRootBootScript = preferences.getBoolean(
                KeyEnableRootBootScript,
                defaults.enableRootBootScript,
            ),
            enableRootIpv6Disabler = preferences.getBoolean(
                KeyEnableRootIpv6Disabler,
                defaults.enableRootIpv6Disabler,
            ),
            socks5ProxyPort = preferences.getString(
                KeySocks5ProxyPort,
                defaults.socks5ProxyPort,
            ) ?: defaults.socks5ProxyPort,
            enableHttpProxy = preferences.getBoolean(KeyEnableHttpProxy, defaults.enableHttpProxy),
            httpProxyPort = preferences.getString(KeyHttpProxyPort, defaults.httpProxyPort) ?: defaults.httpProxyPort,
            externalInterfaces = preferences.getStringList(KeyExternalInterfaces, defaults.externalInterfaces),
            ignoredInterfaces = preferences.getStringList(KeyIgnoredInterfaces, defaults.ignoredInterfaces),
            privateAddressCidrs = preferences.getStringList(KeyPrivateAddressCidrs, defaults.privateAddressCidrs),
            proxyAppListMode = preferences.getInt(KeyProxyAppListMode, defaults.proxyAppListMode),
        )
    }

    fun save(state: AppState) {
        preferences.edit { putAppState(state) }
    }

    private fun SharedPreferences.Editor.putAppState(state: AppState): SharedPreferences.Editor {
        return putInt(KeyColorMode, state.colorMode)
            .putInt(KeyLanguageMode, state.languageMode)
            .putInt(KeySeedIndex, state.seedIndex)
            .putInt(KeyNextSubscriptionGroupId, state.nextSubscriptionGroupId)
            .putBoolean(KeyEnableAllProxyGroup, state.enableAllProxyGroup)
            .putInt(KeyRunMode, state.runMode)
            .putBoolean(KeyEnableResolveProxyServerDomain, state.enableResolveProxyServerDomain)
            .putBoolean(KeyEnableVpnLocalDns, state.enableVpnLocalDns)
            .putString(KeyLocalProxyPort, state.localProxyPort)
            .putBoolean(KeyEnableDynamicLocalProxyPort, state.enableDynamicLocalProxyPort)
            .putBoolean(KeyLocalProxyListenAllInterfaces, state.localProxyListenAllInterfaces)
            .putString(KeyLocalProxyUsername, state.localProxyUsername)
            .putString(KeyLocalProxyPassword, state.localProxyPassword)
            .putBoolean(KeyEnableVpnAppendHttpProxy, state.enableVpnAppendHttpProxy)
            .putString(KeyTunMtu, state.tunMtu)
            .putString(KeyTunVpnDns, state.tunVpnDns)
            .putString(KeyTunIpv4Cidr, state.tunIpv4Cidr)
            .putString(KeyTunIpv6Cidr, state.tunIpv6Cidr)
            .putInt(KeyNextProxyServerId, state.nextProxyServerId)
            .putInt(KeySelectedProxyServerId, state.selectedProxyServerId)
            .putInt(KeyRouteDomainStrategy, state.routeDomainStrategy)
            .putString(KeyDefaultRouteOutboundTag, state.defaultRouteOutboundTag)
            .putInt(KeyNextRouteRuleId, state.nextRouteRuleId)
            .putInt(KeyCoreLogLevel, state.coreLogLevel)
            .putBoolean(KeyEnableAccessLog, state.enableAccessLog)
            .putInt(KeyResourceFileSource, state.resourceFileSource)
            .putString(KeyCustomResourceFileGeoIpUrl, state.customResourceFileGeoIpUrl)
            .putString(KeyCustomResourceFileGeoSiteUrl, state.customResourceFileGeoSiteUrl)
            .putString(KeyCustomResourceFileGeoIpOnlyCnPrivateUrl, state.customResourceFileGeoIpOnlyCnPrivateUrl)
            .putCustomResourceFileList(KeyCustomResourceFiles, state.customResourceFiles)
            .putInt(KeyNextCustomResourceFileId, state.nextCustomResourceFileId)
            .putBoolean(KeyEnableSniffing, state.enableSniffing)
            .putBoolean(KeyEnableSniffingRouteOnly, state.enableSniffingRouteOnly)
            .putBoolean(KeyEnableMux, state.enableMux)
            .putString(KeyMuxConcurrency, state.muxConcurrency)
            .putString(KeyMuxXudpConcurrency, state.muxXudpConcurrency)
            .putInt(KeyMuxXudpProxyUdp443, state.muxXudpProxyUdp443)
            .putBoolean(KeyEnableFragment, state.enableFragment)
            .putString(KeyFragmentPackets, state.fragmentPackets)
            .putString(KeyFragmentLength, state.fragmentLength)
            .putString(KeyFragmentInterval, state.fragmentInterval)
            .putBoolean(KeyEnableIpv6, state.enableIpv6)
            .putBoolean(KeyEnableIpv6Prefer, state.enableIpv6Prefer)
            .putBoolean(KeyEnableFakeDns, state.enableFakeDns)
            .putStringList(KeyProxyDns, state.proxyDns)
            .putStringList(KeyDirectDns, state.directDns)
            .putStringList(KeyDirectDnsDomains, state.directDnsDomains)
            .putBoolean(KeyEnableDirectDnsForProxyServerDomains, state.enableDirectDnsForProxyServerDomains)
            .putStringList(KeyDnsHosts, state.dnsHosts)
            .putString(KeyTransparentProxyPort, state.transparentProxyPort)
            .putBoolean(KeyEnableRootBootScript, state.enableRootBootScript)
            .putBoolean(KeyEnableRootIpv6Disabler, state.enableRootIpv6Disabler)
            .putString(KeySocks5ProxyPort, state.socks5ProxyPort)
            .putBoolean(KeyEnableHttpProxy, state.enableHttpProxy)
            .putString(KeyHttpProxyPort, state.httpProxyPort)
            .putStringList(KeyExternalInterfaces, state.externalInterfaces)
            .putStringList(KeyIgnoredInterfaces, state.ignoredInterfaces)
            .putStringList(KeyPrivateAddressCidrs, state.privateAddressCidrs)
            .putInt(KeyProxyAppListMode, state.proxyAppListMode)
    }

    private fun SharedPreferences.getStringList(key: String, defaultValue: List<String>): List<String> {
        return getString(key, null)?.let(StringListJson::decode) ?: defaultValue
    }

    private fun SharedPreferences.Editor.putStringList(
        key: String,
        values: List<String>,
    ): SharedPreferences.Editor {
        return putString(key, StringListJson.encode(values))
    }

    private fun SharedPreferences.getCustomResourceFileList(
        key: String,
        defaultValue: List<CustomResourceFileState>,
    ): List<CustomResourceFileState> {
        return getString(key, null)?.let(CustomResourceFileListJson::decode) ?: defaultValue
    }

    private fun SharedPreferences.Editor.putCustomResourceFileList(
        key: String,
        values: List<CustomResourceFileState>,
    ): SharedPreferences.Editor {
        return putString(key, CustomResourceFileListJson.encode(values))
    }
}

private const val PreferencesName = "asteriskng_settings"
private const val KeyColorMode = "color_mode"
private const val KeyLanguageMode = "language_mode"
private const val KeySeedIndex = "seed_index"
private const val KeyNextSubscriptionGroupId = "next_subscription_group_id"
private const val KeyEnableAllProxyGroup = "enable_all_proxy_group"
private const val KeyRunMode = "run_mode"
private const val KeyEnableResolveProxyServerDomain = "enable_resolve_proxy_server_domain"
private const val KeyEnableVpnLocalDns = "enable_vpn_local_dns"
private const val KeyLocalProxyPort = "local_proxy_port"
private const val KeyEnableDynamicLocalProxyPort = "enable_dynamic_local_proxy_port"
private const val KeyLocalProxyListenAllInterfaces = "local_proxy_listen_all_interfaces"
private const val KeyLocalProxyUsername = "local_proxy_username"
private const val KeyLocalProxyPassword = "local_proxy_password"
private const val KeyEnableVpnAppendHttpProxy = "enable_vpn_append_http_proxy"
private const val KeyTunMtu = "tun_mtu"
private const val KeyTunVpnDns = "tun_vpn_dns"
private const val KeyTunIpv4Cidr = "tun_ipv4_cidr"
private const val KeyTunIpv6Cidr = "tun_ipv6_cidr"
private const val KeyNextProxyServerId = "next_proxy_server_id"
private const val KeySelectedProxyServerId = "selected_proxy_server_id"
private const val KeyRouteDomainStrategy = "route_domain_strategy"
private const val KeyDefaultRouteOutboundTag = "default_route_outbound_tag"
private const val KeyNextRouteRuleId = "next_route_rule_id"
private const val KeyCoreLogLevel = "core_log_level"
private const val KeyEnableAccessLog = "enable_access_log"
private const val KeyResourceFileSource = "resource_file_source"
private const val KeyCustomResourceFileGeoIpUrl = "custom_resource_file_geoip_url"
private const val KeyCustomResourceFileGeoSiteUrl = "custom_resource_file_geosite_url"
private const val KeyCustomResourceFileGeoIpOnlyCnPrivateUrl = "custom_resource_file_geoip_only_cn_private_url"
private const val KeyCustomResourceFiles = "custom_resource_files"
private const val KeyNextCustomResourceFileId = "next_custom_resource_file_id"
private const val KeyEnableSniffing = "enable_sniffing"
private const val KeyEnableSniffingRouteOnly = "enable_sniffing_route_only"
private const val KeyEnableMux = "enable_mux"
private const val KeyMuxConcurrency = "mux_concurrency"
private const val KeyMuxXudpConcurrency = "mux_xudp_concurrency"
private const val KeyMuxXudpProxyUdp443 = "mux_xudp_proxy_udp_443"
private const val KeyEnableFragment = "enable_fragment"
private const val KeyFragmentPackets = "fragment_packets"
private const val KeyFragmentLength = "fragment_length"
private const val KeyFragmentInterval = "fragment_interval"
private const val KeyEnableIpv6 = "enable_ipv6"
private const val KeyEnableIpv6Prefer = "enable_ipv6_prefer"
private const val KeyEnableFakeDns = "enable_fake_dns"
private const val KeyProxyDns = "proxy_dns"
private const val KeyDirectDns = "direct_dns"
private const val KeyDirectDnsDomains = "direct_dns_domains"
private const val KeyEnableDirectDnsForProxyServerDomains = "enable_direct_dns_for_proxy_server_domains"
private const val KeyDnsHosts = "dns_hosts"
private const val KeyTransparentProxyPort = "transparent_proxy_port"
private const val KeyEnableRootBootScript = "enable_root_boot_script"
private const val KeyEnableRootIpv6Disabler = "enable_root_ipv6_disabler"
private const val KeySocks5ProxyPort = "socks5_proxy_port"
private const val KeyEnableHttpProxy = "enable_http_proxy"
private const val KeyHttpProxyPort = "http_proxy_port"
private const val KeyExternalInterfaces = "external_interfaces"
private const val KeyIgnoredInterfaces = "ignored_interfaces"
private const val KeyPrivateAddressCidrs = "private_address_cidrs"
private const val KeyProxyAppListMode = "proxy_app_list_mode"
