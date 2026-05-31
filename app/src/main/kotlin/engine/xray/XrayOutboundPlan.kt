// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.xray

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

internal enum class XrayRouteTargetKind {
    Outbound,
    Balancer,
}

internal data class XrayRouteTarget(
    val tag: String,
    val kind: XrayRouteTargetKind,
) {
    fun applyTo(builder: JsonObjectBuilder) {
        when (kind) {
            XrayRouteTargetKind.Outbound -> builder.put("outboundTag", tag)
            XrayRouteTargetKind.Balancer -> builder.put("balancerTag", tag)
        }
    }
}

internal data class XrayBalancerPlan(
    val tag: String,
    val selector: String,
    val strategy: String,
)

internal data class XrayOutboundPlan(
    val proxyOutbounds: List<XrayProxyOutboundServer>,
    val balancers: List<XrayBalancerPlan>,
    val observatorySelectors: List<String>,
    val burstObservatorySelectors: List<String>,
    val routeTargets: Map<String, XrayRouteTarget>,
    val dnsHostServers: List<String>,
)
