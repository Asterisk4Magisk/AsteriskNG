// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app

import androidx.compose.runtime.Stable
import features.resources.ResourceFileChocolate4UGeoIpUrl
import features.resources.ResourceFileChocolate4UGeoSiteUrl
import features.resources.ResourceFileGeoIpName
import features.resources.ResourceFileGeoSiteName
import features.resources.ResourceFileLoyalsoldierGeoIpUrl
import features.resources.ResourceFileLoyalsoldierGeoSiteUrl
import features.resources.ResourceFileSourceChocolate4UGithub
import features.resources.ResourceFileSourceLoyalsoldierGithub
import features.resources.ResourceFileSourceV2FlyGithub
import features.resources.ResourceFileV2FlyGeoIpUrl
import features.resources.ResourceFileV2FlyGeoSiteUrl
import features.resources.ResourceFileXrayCoreName
import features.resources.XrayCoreVersion
import features.proxy.server.model.ProxyServer

@Stable
data class SubscriptionGroupState(
    val id: Int,
    val name: String,
    val url: String,
    val userAgent: String,
    val updateInterval: String,
    val updateViaProxy: Boolean = false,
    val enabled: Boolean,
    val builtIn: Boolean = false,
    val lastUpdatedAtMillis: Long = 0L,
)

data class ProxyServerState(
    val id: Int,
    val server: ProxyServer<*>,
    val groupId: Int,
    val latency: String = "",
)

fun ProxyServerState.proxyServerOutboundTag(): String {
    return id.toString()
}

enum class ResourceFileKind(
    val fileName: String,
) {
    GeoIp(ResourceFileGeoIpName),
    GeoSite(ResourceFileGeoSiteName),
    XrayCore(ResourceFileXrayCoreName),
    ;

    val displayName: String
        get() = when (this) {
            GeoIp,
            GeoSite -> fileName
            XrayCore -> "xray-core $XrayCoreVersion"
        }
}

@Stable
data class ResourceFileStatus(
    val exists: Boolean = false,
    val sizeBytes: Long = 0,
    val updatedAtMillis: Long = 0,
)

@Stable
data class ResourceFilesStatus(
    val geoIp: ResourceFileStatus = ResourceFileStatus(),
    val geoSite: ResourceFileStatus = ResourceFileStatus(),
    val xrayCore: ResourceFileStatus = ResourceFileStatus(),
)

data class ResourceFileUpdateSource(
    val id: Int,
    val geoIpUrl: String,
    val geoSiteUrl: String,
)

val ResourceFileUpdateSources = listOf(
    ResourceFileUpdateSource(
        id = ResourceFileSourceLoyalsoldierGithub,
        geoIpUrl = ResourceFileLoyalsoldierGeoIpUrl,
        geoSiteUrl = ResourceFileLoyalsoldierGeoSiteUrl,
    ),
    ResourceFileUpdateSource(
        id = ResourceFileSourceV2FlyGithub,
        geoIpUrl = ResourceFileV2FlyGeoIpUrl,
        geoSiteUrl = ResourceFileV2FlyGeoSiteUrl,
    ),
    ResourceFileUpdateSource(
        id = ResourceFileSourceChocolate4UGithub,
        geoIpUrl = ResourceFileChocolate4UGeoIpUrl,
        geoSiteUrl = ResourceFileChocolate4UGeoSiteUrl,
    ),
)

fun resourceFileUpdateSourceAt(index: Int): ResourceFileUpdateSource {
    return ResourceFileUpdateSources.getOrElse(index) { ResourceFileUpdateSources.first() }
}

fun ResourceFilesStatus.statusOf(kind: ResourceFileKind): ResourceFileStatus {
    return when (kind) {
        ResourceFileKind.GeoIp -> geoIp
        ResourceFileKind.GeoSite -> geoSite
        ResourceFileKind.XrayCore -> xrayCore
    }
}
