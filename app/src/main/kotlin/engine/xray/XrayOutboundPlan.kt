package engine.xray

import org.json.JSONObject

internal enum class XrayRouteTargetKind {
    Outbound,
    Balancer,
}

internal data class XrayRouteTarget(
    val tag: String,
    val kind: XrayRouteTargetKind,
) {
    fun applyTo(rule: JSONObject): JSONObject {
        return when (kind) {
            XrayRouteTargetKind.Outbound -> rule.put("outboundTag", tag)
            XrayRouteTargetKind.Balancer -> rule.put("balancerTag", tag)
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
