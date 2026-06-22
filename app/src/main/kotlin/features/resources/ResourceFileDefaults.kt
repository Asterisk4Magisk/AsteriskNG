// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import app.ProjectInfo

const val ResourceFileSourceLoyalsoldierGithub = 0
const val ResourceFileSourceV2FlyGithub = 1
const val ResourceFileSourceChocolate4UGithub = 2
const val ResourceFileSourceRunetFreedomGithub = 3
const val ResourceFileSourceCustom = 4

const val ResourceFileGeoIpName = "geoip.dat"
const val ResourceFileGeoSiteName = "geosite.dat"
const val ResourceFileGeoIpOnlyCnPrivateName = "geoip-only-cn-private.dat"
const val ResourceFileDirectCidrIpv4Name = "direct-cidr-v4.txt"
const val ResourceFileDirectCidrIpv6Name = "direct-cidr-v6.txt"
const val ResourceFileXrayCoreName = "xray"

const val ResourceFileLoyalsoldierGeoIpUrl =
    "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat"
const val ResourceFileLoyalsoldierGeoSiteUrl =
    "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat"
const val ResourceFileV2FlyGeoIpUrl = "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat"
const val ResourceFileV2FlyGeoIpOnlyCnPrivateUrl =
    "https://github.com/v2fly/geoip/releases/latest/download/geoip-only-cn-private.dat"
const val ResourceFileV2FlyGeoSiteUrl = "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat"
const val ResourceFileChocolate4UGeoIpUrl =
    "https://github.com/Chocolate4U/Iran-v2ray-rules/releases/latest/download/geoip.dat"
const val ResourceFileChocolate4UGeoSiteUrl =
    "https://github.com/Chocolate4U/Iran-v2ray-rules/releases/latest/download/geosite.dat"
const val ResourceFileRunetFreedomGeoIpUrl =
    "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geoip.dat"
const val ResourceFileRunetFreedomGeoSiteUrl =
    "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geosite.dat"
const val ResourceFileDirectCidrIpv4Url =
    "https://raw.githubusercontent.com/mayaxcn/china-ip-list/master/chnroute.txt"
const val ResourceFileDirectCidrIpv6Url =
    "https://raw.githubusercontent.com/mayaxcn/china-ip-list/master/chnroute_v6.txt"

const val XrayCoreVersion = ProjectInfo.XRAY_CORE_VERSION
