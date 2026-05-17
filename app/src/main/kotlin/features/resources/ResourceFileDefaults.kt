package features.resources

import app.ProjectInfo

const val ResourceFileSourceLoyalsoldierGithub = 0
const val ResourceFileSourceV2FlyGithub = 1
const val ResourceFileSourceChocolate4UGithub = 2

const val ResourceFileGeoIpName = "geoip.dat"
const val ResourceFileGeoSiteName = "geosite.dat"
const val ResourceFileXrayCoreName = "xray"

const val ResourceFileLoyalsoldierGeoIpUrl =
    "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat"
const val ResourceFileLoyalsoldierGeoSiteUrl =
    "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat"
const val ResourceFileV2FlyGeoIpUrl = "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat"
const val ResourceFileV2FlyGeoSiteUrl = "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat"
const val ResourceFileChocolate4UGeoIpUrl =
    "https://github.com/Chocolate4U/Iran-v2ray-rules/releases/latest/download/geoip.dat"
const val ResourceFileChocolate4UGeoSiteUrl =
    "https://github.com/Chocolate4U/Iran-v2ray-rules/releases/latest/download/geosite.dat"

const val XrayCoreVersion = ProjectInfo.XRAY_CORE_VERSION
