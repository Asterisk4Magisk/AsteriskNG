package engine.xray

import app.AppState
import app.ProxyServerState
import app.effectiveLocalDnsEnabled
import app.proxyServerOutboundTag
import features.routing.model.RouteRule
import features.proxy.server.model.ChainProxy
import features.proxy.server.model.Custom
import features.proxy.server.model.StrategyGroup
import features.proxy.server.model.StrategyGroupConstants
import features.proxy.server.model.customXrayConfigProxyServerHosts
import features.proxy.server.model.isCompositeProxyServer
import features.proxy.server.model.isCustomProxyServer
import features.proxy.server.model.serverHost

internal fun AppState.buildXrayOutboundPlan(selectedServer: ProxyServerState): XrayOutboundPlan {
    return XrayOutboundPlanner(this).build(selectedServer)
}

private class XrayOutboundPlanner(
    private val appState: AppState,
) {
    private val proxyOutbounds = mutableListOf<XrayProxyOutboundServer>()
    private val balancers = mutableListOf<XrayBalancerPlan>()
    private val observatorySelectors = mutableListOf<String>()
    private val burstObservatorySelectors = mutableListOf<String>()
    private val routeTargets = linkedMapOf<String, XrayRouteTarget>()
    private val addedOutboundTags = mutableSetOf<String>()
    private val dnsHostServers = mutableListOf<String>()

    fun build(selectedServer: ProxyServerState): XrayOutboundPlan {
        addRouteTarget(XrayTags.PROXY, selectedServer)
        appState.routeTargetServers().forEach { server ->
            addRouteTarget(server.proxyServerOutboundTag(), server)
        }
        addFixedRouteTargets()
        return XrayOutboundPlan(
            proxyOutbounds = proxyOutbounds,
            balancers = balancers,
            observatorySelectors = observatorySelectors.distinct(),
            burstObservatorySelectors = burstObservatorySelectors.distinct(),
            routeTargets = routeTargets,
            dnsHostServers = dnsHostServers.distinct(),
        )
    }

    private fun addFixedRouteTargets() {
        routeTargets[XrayTags.DIRECT] = XrayRouteTarget(XrayTags.DIRECT, XrayRouteTargetKind.Outbound)
        routeTargets[XrayTags.BLOCK] = XrayRouteTarget(XrayTags.BLOCK, XrayRouteTargetKind.Outbound)
        if (appState.effectiveLocalDnsEnabled) {
            routeTargets[XrayTags.DNS_OUT] = XrayRouteTarget(XrayTags.DNS_OUT, XrayRouteTargetKind.Outbound)
        }
        if (appState.enableFragment) {
            routeTargets[XrayTags.FRAGMENT] = XrayRouteTarget(XrayTags.FRAGMENT, XrayRouteTargetKind.Outbound)
        }
    }

    private fun addRouteTarget(tag: String, server: ProxyServerState) {
        when (val proxyServer = server.server) {
            is StrategyGroup -> addStrategyGroup(tag, proxyServer)
            is ChainProxy -> addChainProxy(tag, proxyServer)
            is Custom -> addCustomDnsHosts(proxyServer)
            else -> addNormalOutbound(tag, server)
        }
    }

    private fun addCustomDnsHosts(proxyServer: Custom) {
        proxyServer.check()
        if (proxyServer.overrideAsteriskInboundAndDns) {
            dnsHostServers += customXrayConfigProxyServerHosts(proxyServer.configJson)
        }
    }

    private fun addNormalOutbound(
        tag: String,
        server: ProxyServerState,
        dialerProxyTag: String? = null,
        allowFragment: Boolean = true,
    ) {
        if (tag in addedOutboundTags) return
        server.server.check()
        proxyOutbounds += XrayProxyOutboundServer(
            tag = tag,
            server = server.server,
            dialerProxyTag = dialerProxyTag,
            allowFragment = allowFragment,
        )
        dnsHostServers += server.server.serverHost()
        routeTargets[tag] = XrayRouteTarget(tag, XrayRouteTargetKind.Outbound)
        addedOutboundTags += tag
    }

    private fun addStrategyGroup(tag: String, strategyGroup: StrategyGroup) {
        strategyGroup.check()
        val members = appState.strategyGroupMembers(strategyGroup)
        if (members.isEmpty()) {
            error("Strategy group '${strategyGroup.remarks}' has no available proxy servers")
        }
        val selector = "$tag-policy-"
        members.forEach { member ->
            addNormalOutbound(
                tag = "$selector${member.id}",
                server = member,
            )
        }
        balancers += XrayBalancerPlan(
            tag = tag,
            selector = selector,
            strategy = strategyGroup.strategy,
        )
        when (strategyGroup.strategy) {
            StrategyGroupConstants.TYPE_LEAST_LOAD -> burstObservatorySelectors += selector
            StrategyGroupConstants.TYPE_LEAST_PING -> observatorySelectors += selector
        }
        routeTargets[tag] = XrayRouteTarget(tag, XrayRouteTargetKind.Balancer)
    }

    private fun addChainProxy(tag: String, chainProxy: ChainProxy) {
        chainProxy.check()
        val members = appState.chainProxyMembers(chainProxy)
        if (members.size < 2) {
            error("Proxy chain '${chainProxy.remarks}' requires at least two available proxy servers")
        }
        val chainOutbounds = members.reversed()
        chainOutbounds.forEachIndexed { index, member ->
            addNormalOutbound(
                tag = chainProxyOutboundTag(tag, index),
                server = member,
                dialerProxyTag = if (index < chainOutbounds.lastIndex) chainProxyOutboundTag(tag, index + 1) else null,
                allowFragment = false,
            )
        }
        routeTargets[tag] = XrayRouteTarget(tag, XrayRouteTargetKind.Outbound)
    }
}

private fun AppState.routeTargetServers(): List<ProxyServerState> {
    val routeOutboundTags = (routeRules
        .filter(RouteRule::enabled)
        .map { rule -> rule.outboundTag } + defaultRouteOutboundTag)
        .map { tag -> tag.trim() }
        .filter { tag -> tag.isNotEmpty() && tag !in XrayTags.FIXED_OUTBOUND_TAGS }
        .toSet()
    return proxyServers.filter { server -> server.proxyServerOutboundTag() in routeOutboundTags }
}

private fun AppState.strategyGroupMembers(strategyGroup: StrategyGroup): List<ProxyServerState> {
    val regex = strategyGroup.filter.takeIf(String::isNotBlank)?.let { filter ->
        runCatching { Regex(filter) }.getOrNull()
    }
    return proxyServers
        .asSequence()
        .filter { server -> !server.server.isCompositeProxyServer() }
        .filter { server -> !server.server.isCustomProxyServer() }
        .filter { server ->
            strategyGroup.subscriptionGroupId == null || server.groupId == strategyGroup.subscriptionGroupId
        }
        .filter { server ->
            val filter = strategyGroup.filter
            filter.isBlank() ||
                regex?.containsMatchIn(server.server.getInfo().remarks) == true ||
                (regex == null && server.server.getInfo().remarks.contains(filter))
        }
        .filter { server -> runCatching { server.server.check() }.isSuccess }
        .toList()
}

private fun AppState.chainProxyMembers(chainProxy: ChainProxy): List<ProxyServerState> {
    return chainProxy.proxyServerIds.mapNotNull { memberId ->
        proxyServers.firstOrNull { server -> server.id == memberId && !server.server.isCompositeProxyServer() }
            ?.takeUnless { server -> server.server.isCustomProxyServer() }
    }
}

private fun chainProxyOutboundTag(tag: String, index: Int): String {
    return if (index == 0) tag else "$tag-chain-$index"
}
