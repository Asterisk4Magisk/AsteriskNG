package data

import app.AppState

internal data class PersistedAppState(
    val settings: AppSettingsEntity,
    val remoteDnsServers: List<RemoteDnsServerEntity>,
    val domesticDnsServers: List<DomesticDnsServerEntity>,
    val dnsHosts: List<DnsHostEntity>,
    val externalNetworkInterfaces: List<ExternalNetworkInterfaceEntity>,
    val ignoredNetworkInterfaces: List<IgnoredNetworkInterfaceEntity>,
    val tproxyPrivateAddressCidrs: List<TproxyPrivateAddressCidrEntity>,
    val subscriptionGroups: List<SubscriptionGroupEntity>,
    val proxyServers: List<ProxyServerEntity>,
    val routingRules: List<RouteRuleEntity>,
    val proxyAppListSelectedApps: List<ProxyAppListSelectedAppEntity>,
) {
    fun toAppState(): AppState {
        val restoredProxyServerList = proxyServers.mapNotNull { server -> server.toState() }
        val restoredSelectedProxyServerId = settings.selectedProxyServerId
            .takeIf { serverId -> restoredProxyServerList.any { server -> server.id == serverId } }
            ?: restoredProxyServerList.firstOrNull()?.id
            ?: settings.selectedProxyServerId

        return AppState(
            colorMode = settings.colorMode,
            languageMode = settings.languageMode,
            seedIndex = settings.seedIndex,
            subscriptionGroups = subscriptionGroups.map { group -> group.toState() },
            nextSubscriptionGroupId = settings.nextSubscriptionGroupId,
            enableAllProxyGroup = settings.enableAllProxyGroup,
            runMode = settings.runMode,
            enableResolveProxyServerDomain = settings.enableResolveProxyServerDomain,
            enableVpnLocalDns = settings.enableVpnLocalDns,
            localProxyPort = settings.localProxyPort,
            enableDynamicLocalProxyPort = settings.enableDynamicLocalProxyPort,
            localProxyListenAllInterfaces = settings.localProxyListenAllInterfaces,
            localProxyUsername = settings.localProxyUsername,
            localProxyPassword = settings.localProxyPassword,
            enableVpnAppendHttpProxy = settings.enableVpnAppendHttpProxy,
            vpnMtu = settings.vpnMtu,
            vpnDefaultDns = settings.vpnDefaultDns,
            vpnIpv4Cidr = settings.vpnIpv4Cidr,
            vpnIpv6Cidr = settings.vpnIpv6Cidr,
            proxyServers = restoredProxyServerList,
            nextProxyServerId = settings.nextProxyServerId,
            selectedProxyServerId = restoredSelectedProxyServerId,
            proxyRunning = false,
            routeDomainStrategy = settings.routeDomainStrategy,
            routeRules = routingRules.map { rule -> rule.toState() },
            nextRouteRuleId = settings.nextRouteRuleId,
            coreLogLevel = settings.coreLogLevel,
            enableAccessLog = settings.enableAccessLog,
            resourceFileSource = settings.resourceFileSource,
            enableSniffing = settings.enableSniffing,
            enableSniffingRouteOnly = settings.enableSniffingRouteOnly,
            enableMux = settings.enableMux,
            muxConcurrency = settings.muxConcurrency,
            muxXudpConcurrency = settings.muxXudpConcurrency,
            muxXudpProxyUdp443 = settings.muxXudpProxyUdp443,
            enableFragment = settings.enableFragment,
            fragmentPackets = settings.fragmentPackets,
            fragmentLength = settings.fragmentLength,
            fragmentInterval = settings.fragmentInterval,
            enableIpv6 = settings.enableIpv6,
            enableIpv6Prefer = settings.enableIpv6Prefer,
            enableFakeDns = settings.enableFakeDns,
            remoteDns = remoteDnsServers.map { server -> server.value },
            domesticDns = domesticDnsServers.map { server -> server.value },
            dnsHosts = dnsHosts.map { host -> host.value },
            transparentProxyPort = settings.transparentProxyPort,
            enableTproxyBootScript = settings.enableTproxyBootScript,
            enableSocks5Proxy = settings.enableSocks5Proxy,
            socks5ProxyPort = settings.socks5ProxyPort,
            enableHttpProxy = settings.enableHttpProxy,
            httpProxyPort = settings.httpProxyPort,
            externalInterfaces = externalNetworkInterfaces.map { networkInterface -> networkInterface.value },
            ignoredInterfaces = ignoredNetworkInterfaces.map { networkInterface -> networkInterface.value },
            privateAddressCidrs = tproxyPrivateAddressCidrs.map { cidr -> cidr.value },
            proxyAppListMode = settings.proxyAppListMode,
            proxyAppListSelectedApps = proxyAppListSelectedApps.map { app -> app.packageKey },
        )
    }
}
