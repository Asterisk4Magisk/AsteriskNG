// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app

import features.routing.model.RouteRule
import features.subscription.DefaultSubscriptionGroupId
import features.subscription.DefaultSubscriptionUserAgent

const val DefaultRouteOutboundTag = "proxy"

val DefaultSubscriptionGroups = listOf(
    SubscriptionGroupState(
        id = DefaultSubscriptionGroupId,
        name = "默认",
        url = "",
        userAgent = DefaultSubscriptionUserAgent,
        updateInterval = "",
        enabled = true,
        builtIn = true,
    ),
)

val DefaultRouteRules = listOf(
    RouteRule(
        id = 1,
        remarks = "ad_blocker",
        outboundTag = "block",
        domain = listOf("geosite:category-ads-all"),
        port = "",
        protocol = "",
        network = "",
        enabled = false,
    ),
    RouteRule(
        id = 2,
        remarks = "block_udp_443",
        outboundTag = "block",
        port = "443",
        protocol = "",
        network = "udp",
        enabled = true,
    ),
    RouteRule(
        id = 3,
        remarks = "non-china_site",
        outboundTag = DefaultRouteOutboundTag,
        domain = listOf("geosite:google", "geosite:geolocation-!cn"),
        port = "",
        protocol = "",
        network = "",
        enabled = true,
    ),
    RouteRule(
        id = 4,
        remarks = "china_site",
        outboundTag = "direct",
        domain = listOf("geosite:cn", "geosite:private"),
        port = "",
        protocol = "",
        network = "",
        enabled = true,
    ),
    RouteRule(
        id = 5,
        remarks = "china_ip",
        outboundTag = "direct",
        ip = listOf("geoip:cn", "geoip:private"),
        port = "",
        protocol = "",
        network = "",
        enabled = true,
    ),
)
