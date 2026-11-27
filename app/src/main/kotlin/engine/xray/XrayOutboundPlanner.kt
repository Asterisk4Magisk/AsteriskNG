// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.xray

import app.AppState
import app.ProxyServerState
import app.effectiveLocalDnsEnabled
import app.proxyServerOutboundTag
import features.proxy.server.model.strategyGroupMembers
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
    private val failoverRoutes = mutableListOf<XrayFailoverRoute>()
    private val failoverSelectors = mutableListOf<String>()
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
        // Both observer types expose the same Xray feature. Reuse burst when leastLoad needs its samples.
        val hasFailover = failoverSelectors.isNotEmpty()
        val useBurstForFailover = hasFailover && burstObservatorySelectors.isNotEmpty()
        val sharedSelectors = observatorySelectors + failoverSelectors
        return XrayOutboundPlan(
            proxyOutbounds = proxyOutbounds,
            balancers = balancers,
            failoverRoutes = failoverRoutes,
            hasFailover = hasFailover,
            observatorySelectors = if (useBurstForFailover) emptyList() else sharedSelectors.distinct(),
            burstObservatorySelectors = if (useBurstForFailover) {
                (burstObservatorySelectors + sharedSelectors).distinct()
            } else {
                burstObservatorySelectors.distinct()
            },
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
        val members = appState.strategyGroupMembers(strategyGroup)
        if (members.isEmpty()) {
            error("Strategy group '${strategyGroup.remarks}' has no available proxy servers")
        }
        val isFailover = strategyGroup.strategy == StrategyGroupConstants.TYPE_FAILOVER
        if (isFailover && members.size == 1) {
            addNormalOutbound(tag, members.single())
            return
        }
        val selector = "$tag-policy-"
        val memberTags = members.map { member ->
            // The trailing separator keeps a singleton prefix selector from matching another member ID.
            val memberTag = "$selector${member.id}" + if (isFailover) "-" else ""
            addNormalOutbound(tag = memberTag, server = member)
            memberTag
        }
        if (isFailover) {
            addFailoverBalancers(tag, memberTags)
            failoverSelectors += selector
        } else {
            balancers += XrayBalancerPlan(
                tag = tag,
                selector = selector,
                strategy = strategyGroup.strategy,
                fallbackTag = memberTags.first(),
            )
            when (strategyGroup.strategy) {
                StrategyGroupConstants.TYPE_LEAST_LOAD -> burstObservatorySelectors += selector
                StrategyGroupConstants.TYPE_LEAST_PING -> observatorySelectors += selector
            }
        }
        routeTargets[tag] = XrayRouteTarget(tag, XrayRouteTargetKind.Balancer)
    }

    private fun addFailoverBalancers(tag: String, memberTags: List<String>) {
        memberTags.forEachIndexed { index, memberTag ->
            val fallbackTag = if (index < memberTags.lastIndex) {
                val nextBalancerTag = "$tag-failover-${index + 1}"
                val loopbackTag = "$nextBalancerTag-loopback"
                failoverRoutes += XrayFailoverRoute(loopbackTag, nextBalancerTag)
                loopbackTag
            } else {
                // When every member has failed, try the primary outbound directly.
                memberTags.first()
            }
            balancers += XrayBalancerPlan(
                tag = if (index == 0) tag else "$tag-failover-$index",
                selector = memberTag,
                // A single candidate stays eligible until the observer records a failure.
                strategy = StrategyGroupConstants.TYPE_RANDOM,
                fallbackTag = fallbackTag,
            )
        }
    }

    private fun addChainProxy(tag: String, chainProxy: ChainProxy) {
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

private fun AppState.chainProxyMembers(chainProxy: ChainProxy): List<ProxyServerState> {
    return chainProxy.proxyServerIds.mapNotNull { memberId ->
        proxyServers.firstOrNull { server -> server.id == memberId && !server.server.isCompositeProxyServer() }
            ?.takeUnless { server -> server.server.isCustomProxyServer() }
    }
}

private fun chainProxyOutboundTag(tag: String, index: Int): String {
    return if (index == 0) tag else "$tag-chain-$index"
}
