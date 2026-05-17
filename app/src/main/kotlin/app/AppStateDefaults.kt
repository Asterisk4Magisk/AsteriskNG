// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app

import features.routing.model.RouteRule
import features.subscription.DefaultSubscriptionGroupId
import features.subscription.DefaultSubscriptionUserAgent

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
        remarks = "广告与跟踪拦截",
        outboundTag = "block",
        domain = listOf("geosite:category-ads-all"),
        port = "",
        protocol = "",
        network = "",
        enabled = true,
    ),
    RouteRule(
        id = 2,
        remarks = "国内站点直连",
        outboundTag = "direct",
        domain = listOf("geosite:cn", "geosite:private"),
        port = "",
        protocol = "",
        network = "",
        enabled = true,
    ),
    RouteRule(
        id = 3,
        remarks = "国内IP直连",
        outboundTag = "direct",
        domain = listOf("geoip:cn", "geoip:private"),
        port = "",
        protocol = "",
        network = "",
        enabled = true,
    ),
)
