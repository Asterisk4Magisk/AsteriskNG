// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package data.backup

import app.AppState
import app.CustomResourceFileState
import app.ProxyServerState
import app.SubscriptionGroupState
import app.modes.RunModeVpnService
import data.PersistedProxyServer
import data.decodeProxyServer
import data.toPersistedProxyServer
import features.proxy.server.model.ChainProxy
import features.routing.model.RouteRule
import features.subscription.DefaultSubscriptionGroupId

internal fun AppState.toAppBackupFile(
    createdAtMillis: Long,
    appVersionName: String,
    appVersionCode: Int,
): AppBackupFile {
    return AppBackupFile(
        format = AppBackupFormat,
        version = CurrentAppBackupVersion,
        createdAtMillis = createdAtMillis,
        appVersionName = appVersionName,
        appVersionCode = appVersionCode,
        data = AppBackupData(
            settings = toBackupSettings(),
            subscriptionGroups = subscriptionGroups.map(SubscriptionGroupState::toBackup),
            proxyServers = proxyServers.map(ProxyServerState::toBackup),
            routeRules = routeRules.map(RouteRule::toBackup),
            proxyAppListSelectedApps = proxyAppListSelectedApps,
        ),
    )
}

internal fun AppBackupFile.toRestorePreview(): AppBackupRestorePreview {
    val migrated = migrateAppBackup()
    val state = migrated.data.toAppState()
    return AppBackupRestorePreview(
        backup = migrated,
        restoredState = state,
        warnings = state.restoreWarnings(),
    )
}

private fun AppState.toBackupSettings(): AppBackupSettings {
    return AppBackupSettings(
        colorMode = colorMode,
        languageMode = languageMode,
        seedIndex = seedIndex,
        enableAllProxyGroup = enableAllProxyGroup,
        enableResolveProxyServerDomain = enableResolveProxyServerDomain,
        enableVpnLocalDns = enableVpnLocalDns,
        localProxyPort = localProxyPort,
        enableDynamicLocalProxyPort = enableDynamicLocalProxyPort,
        localProxyListenAllInterfaces = localProxyListenAllInterfaces,
        localProxyUsername = localProxyUsername,
        localProxyPassword = localProxyPassword,
        enableVpnAppendHttpProxy = enableVpnAppendHttpProxy,
        enableVpnHevTun = enableVpnHevTun,
        tunMtu = tunMtu,
        tunVpnDns = tunVpnDns,
        tunIpv4Cidr = tunIpv4Cidr,
        tunIpv6Cidr = tunIpv6Cidr,
        selectedProxyServerId = selectedProxyServerId,
        proxyServerListLayout = proxyServerListLayout,
        proxyServerListSort = proxyServerListSort,
        routeDomainStrategy = routeDomainStrategy,
        defaultRouteOutboundTag = defaultRouteOutboundTag,
        coreLogLevel = coreLogLevel,
        enableAccessLog = enableAccessLog,
        resourceFileSource = resourceFileSource,
        customResourceFileGeoIpUrl = customResourceFileGeoIpUrl,
        customResourceFileGeoSiteUrl = customResourceFileGeoSiteUrl,
        customResourceFileGeoIpOnlyCnPrivateUrl = customResourceFileGeoIpOnlyCnPrivateUrl,
        customResourceFileDirectCidrIpv4Url = customResourceFileDirectCidrIpv4Url,
        customResourceFileDirectCidrIpv6Url = customResourceFileDirectCidrIpv6Url,
        customResourceFiles = customResourceFiles.map(CustomResourceFileState::toBackup),
        enableSniffing = enableSniffing,
        enableSniffingRouteOnly = enableSniffingRouteOnly,
        enableMux = enableMux,
        muxConcurrency = muxConcurrency,
        muxXudpConcurrency = muxXudpConcurrency,
        muxXudpProxyUdp443 = muxXudpProxyUdp443,
        enableFragment = enableFragment,
        fragmentPackets = fragmentPackets,
        fragmentLength = fragmentLength,
        fragmentInterval = fragmentInterval,
        enableTrafficStatsNotification = enableTrafficStatsNotification,
        enableIpv6 = enableIpv6,
        enableIpv6Prefer = enableIpv6Prefer,
        enableFakeDns = enableFakeDns,
        proxyDns = proxyDns,
        directDns = directDns,
        directDnsDomains = directDnsDomains,
        enableDirectDnsForProxyServerDomains = enableDirectDnsForProxyServerDomains,
        dnsHosts = dnsHosts,
        transparentProxyPort = transparentProxyPort,
        enableRootEbpfRules = enableRootEbpfRules,
        enableRootEbpfDirectCidrBypass = enableRootEbpfDirectCidrBypass,
        enableRootIpv6Disabler = enableRootIpv6Disabler,
        bpf2SocksBridgePort = bpf2SocksBridgePort,
        socks5ProxyPort = socks5ProxyPort,
        enableHttpProxy = enableHttpProxy,
        httpProxyPort = httpProxyPort,
        externalInterfaces = externalInterfaces,
        ignoredInterfaces = ignoredInterfaces,
        privateAddressCidrs = privateAddressCidrs,
        proxyAppListMode = proxyAppListMode,
    )
}

private fun SubscriptionGroupState.toBackup(): AppBackupSubscriptionGroup {
    return AppBackupSubscriptionGroup(
        id = id,
        name = name,
        url = url,
        userAgent = userAgent,
        updateInterval = updateInterval,
        updateViaProxy = updateViaProxy,
        enabled = enabled,
        builtIn = builtIn,
        lastUpdatedAtMillis = lastUpdatedAtMillis,
    )
}

private fun ProxyServerState.toBackup(): AppBackupProxyServer {
    val persistedServer = server.toPersistedProxyServer()
    return AppBackupProxyServer(
        id = id,
        groupId = groupId,
        protocol = persistedServer.protocol,
        payload = persistedServer.payload,
    )
}

private fun RouteRule.toBackup(): AppBackupRouteRule {
    return AppBackupRouteRule(
        id = id,
        remarks = remarks,
        outboundTag = outboundTag,
        domain = domain,
        ip = ip,
        process = process,
        port = port,
        protocol = protocol,
        network = network,
        enabled = enabled,
    )
}

private fun CustomResourceFileState.toBackup(): AppBackupCustomResourceFile {
    return AppBackupCustomResourceFile(
        id = id,
        name = name,
        url = url,
    )
}

private fun AppBackupData.toAppState(): AppState {
    val defaults = AppState()
    val restoredSubscriptionGroups = subscriptionGroups
        .map(AppBackupSubscriptionGroup::toState)
        .ifEmpty { defaults.subscriptionGroups }
    val groupIds = restoredSubscriptionGroups.mapTo(mutableSetOf()) { group -> group.id }
    val fallbackGroupId = restoredSubscriptionGroups.firstOrNull { group -> group.builtIn }?.id
        ?: restoredSubscriptionGroups.firstOrNull()?.id
        ?: DefaultSubscriptionGroupId
    val restoredProxyServers = proxyServers.map { server ->
        server.toState(
            validGroupIds = groupIds,
            fallbackGroupId = fallbackGroupId,
        )
    }
    val restoredSelectedProxyServerId = settings.selectedProxyServerId
        .takeIf { serverId -> restoredProxyServers.any { server -> server.id == serverId } }
        ?: restoredProxyServers.firstOrNull()?.id
        ?: defaults.selectedProxyServerId
    val restoredCustomResourceFiles = settings.customResourceFiles.map(AppBackupCustomResourceFile::toState)
    val restoredRouteRules = routeRules.map(AppBackupRouteRule::toState)

    return defaults.copy(
        colorMode = settings.colorMode,
        languageMode = settings.languageMode,
        seedIndex = settings.seedIndex,
        subscriptionGroups = restoredSubscriptionGroups,
        nextSubscriptionGroupId = nextId(
            defaultValue = defaults.nextSubscriptionGroupId,
            ids = restoredSubscriptionGroups.map { group -> group.id },
        ),
        enableAllProxyGroup = settings.enableAllProxyGroup,
        runMode = RunModeVpnService,
        enableResolveProxyServerDomain = settings.enableResolveProxyServerDomain,
        enableVpnLocalDns = settings.enableVpnLocalDns,
        localProxyPort = settings.localProxyPort,
        enableDynamicLocalProxyPort = settings.enableDynamicLocalProxyPort,
        localProxyListenAllInterfaces = settings.localProxyListenAllInterfaces,
        localProxyUsername = settings.localProxyUsername,
        localProxyPassword = settings.localProxyPassword,
        enableVpnAppendHttpProxy = settings.enableVpnAppendHttpProxy,
        enableVpnHevTun = settings.enableVpnHevTun,
        tunMtu = settings.tunMtu,
        tunVpnDns = settings.tunVpnDns,
        tunIpv4Cidr = settings.tunIpv4Cidr,
        tunIpv6Cidr = settings.tunIpv6Cidr,
        proxyServers = restoredProxyServers,
        nextProxyServerId = nextId(
            defaultValue = defaults.nextProxyServerId,
            ids = restoredProxyServers.map { server -> server.id },
        ),
        selectedProxyServerId = restoredSelectedProxyServerId,
        proxyServerListLayout = settings.proxyServerListLayout,
        proxyServerListSort = settings.proxyServerListSort,
        proxyRunning = false,
        routeDomainStrategy = settings.routeDomainStrategy,
        defaultRouteOutboundTag = settings.defaultRouteOutboundTag,
        routeRules = restoredRouteRules,
        nextRouteRuleId = nextId(
            defaultValue = defaults.nextRouteRuleId,
            ids = restoredRouteRules.map { rule -> rule.id },
        ),
        coreLogLevel = settings.coreLogLevel,
        enableAccessLog = settings.enableAccessLog,
        resourceFileSource = settings.resourceFileSource,
        customResourceFileGeoIpUrl = settings.customResourceFileGeoIpUrl,
        customResourceFileGeoSiteUrl = settings.customResourceFileGeoSiteUrl,
        customResourceFileGeoIpOnlyCnPrivateUrl = settings.customResourceFileGeoIpOnlyCnPrivateUrl,
        customResourceFileDirectCidrIpv4Url = settings.customResourceFileDirectCidrIpv4Url,
        customResourceFileDirectCidrIpv6Url = settings.customResourceFileDirectCidrIpv6Url,
        customResourceFiles = restoredCustomResourceFiles,
        nextCustomResourceFileId = nextId(
            defaultValue = defaults.nextCustomResourceFileId,
            ids = restoredCustomResourceFiles.map { file -> file.id },
        ),
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
        enableTrafficStatsNotification = settings.enableTrafficStatsNotification,
        enableIpv6 = settings.enableIpv6,
        enableIpv6Prefer = settings.enableIpv6Prefer,
        enableFakeDns = settings.enableFakeDns,
        proxyDns = settings.proxyDns,
        directDns = settings.directDns,
        directDnsDomains = settings.directDnsDomains,
        enableDirectDnsForProxyServerDomains = settings.enableDirectDnsForProxyServerDomains,
        dnsHosts = settings.dnsHosts,
        transparentProxyPort = settings.transparentProxyPort,
        enableRootBootScript = false,
        enableRootEbpfRules = false,
        enableRootEbpfDirectCidrBypass = settings.enableRootEbpfDirectCidrBypass,
        enableRootIpv6Disabler = settings.enableRootIpv6Disabler,
        bpf2SocksBridgePort = settings.bpf2SocksBridgePort,
        socks5ProxyPort = settings.socks5ProxyPort,
        enableHttpProxy = settings.enableHttpProxy,
        httpProxyPort = settings.httpProxyPort,
        externalInterfaces = settings.externalInterfaces,
        ignoredInterfaces = settings.ignoredInterfaces,
        privateAddressCidrs = settings.privateAddressCidrs,
        proxyAppListMode = settings.proxyAppListMode,
        proxyAppListSelectedApps = proxyAppListSelectedApps,
    )
}

private fun AppBackupSubscriptionGroup.toState(): SubscriptionGroupState {
    return SubscriptionGroupState(
        id = id,
        name = name,
        url = url,
        userAgent = userAgent,
        updateInterval = updateInterval,
        updateViaProxy = updateViaProxy,
        enabled = enabled,
        builtIn = builtIn,
        lastUpdatedAtMillis = lastUpdatedAtMillis,
    )
}

private fun AppBackupProxyServer.toState(
    validGroupIds: Set<Int>,
    fallbackGroupId: Int,
): ProxyServerState {
    return ProxyServerState(
        id = id,
        server = PersistedProxyServer(
            protocol = protocol,
            payload = payload,
        ).decodeProxyServer(),
        groupId = groupId.takeIf { it in validGroupIds } ?: fallbackGroupId,
    )
}

private fun AppBackupRouteRule.toState(): RouteRule {
    return RouteRule(
        id = id,
        remarks = remarks,
        outboundTag = outboundTag,
        domain = domain,
        ip = ip,
        process = process,
        port = port,
        protocol = protocol,
        network = network,
        enabled = enabled,
    )
}

private fun AppBackupCustomResourceFile.toState(): CustomResourceFileState {
    return CustomResourceFileState(
        id = id,
        name = name,
        url = url,
    )
}

private fun AppState.restoreWarnings(): List<AppBackupWarning> {
    val serverIds = proxyServers.mapTo(mutableSetOf()) { server -> server.id }
    val missingChainMemberCount = proxyServers.sumOf { server ->
        (server.server as? ChainProxy)
            ?.proxyServerIds
            ?.count { memberId -> memberId !in serverIds }
            ?: 0
    }
    return buildList {
        if (missingChainMemberCount > 0) {
            add(AppBackupWarning.MissingChainProxyMembers(missingChainMemberCount))
        }
    }
}

private fun nextId(defaultValue: Int, ids: List<Int>): Int {
    return maxOf(defaultValue, (ids.maxOrNull() ?: 0) + 1)
}
