package engine.tproxy

import android.content.Context
import android.os.Process
import app.AppState
import app.effectiveFakeDnsEnabled
import engine.xray.buildXrayOutboundPlan
import engine.vpn.xrayDnsHosts
import engine.xray.XrayConfigFactory
import engine.xray.XrayConfigRequest
import engine.xray.XrayCoreLogPaths
import engine.xray.XrayProtocols
import engine.xray.XrayTags
import engine.xray.prepareXrayCoreLogPaths
import engine.xray.toJsonStringArray
import engine.xray.xraySniffingDestOverrides
import features.resources.runtime.XrayResourceFilePaths
import features.resources.runtime.prepareXrayResourceFilePaths
import system.getApplicationInfoCompat
import system.ANDROID_USER_UID_RANGE
import app.modes.ProxyAppListModeBlacklist
import app.modes.ProxyAppListModeGlobal
import app.modes.ProxyAppListModeWhitelist
import system.toAndroidAppId
import system.toAndroidUserId
import engine.network.NetworkLimits
import engine.proxy.ProxyEngineStartRequest
import org.json.JSONObject
import java.io.File

internal data class TproxyStartConfig(
    val xrayConfigJson: String,
    val setuidgidPath: String,
    val xrayCorePath: String,
    val dataDir: String,
    val configPath: String,
    val pidPath: String,
    val tproxyPort: Int,
    val enableIpv6: Boolean,
    val enableAccessLog: Boolean,
    val coreLogPaths: XrayCoreLogPaths,
    val iptablesConfig: TproxyIptablesConfig,
)

internal data class TproxyRuntimeConfig(
    val xrayCorePath: String,
    val pidPath: String,
)

internal data class TproxyIptablesConfig(
    val uid: Int = TproxyBypassUid,
    val gid: Int = TproxyBypassGid,
    val mark: String = TproxyFwmark,
    val ipv4Table: String = TproxyRouteTable,
    val ipv6Table: String = TproxyRouteTable,
    val externalInterfacePrefixes: List<String> = emptyList(),
    val ignoredInterfaces: List<String> = emptyList(),
    val localInterfaceIpv4Cidrs: List<String> = emptyList(),
    val localInterfaceIpv6Cidrs: List<String> = emptyList(),
    val proxyPrivateIpv4Cidrs: List<String> = emptyList(),
    val proxyPrivateIpv6Cidrs: List<String> = emptyList(),
    val bypassPrivateIpv4Cidrs: List<String> = emptyList(),
    val bypassPrivateIpv6Cidrs: List<String> = emptyList(),
    val proxyAppListMode: Int = ProxyAppListModeGlobal,
    val proxyApplicationUids: List<Int> = emptyList(),
)

internal object TproxyConfigFactory {
    fun runtimeConfig(context: Context): TproxyRuntimeConfig {
        val resourceFilePaths = context.prepareXrayResourceFilePaths()
        val runtimeFiles = resourceFilePaths.toTproxyRuntimeFiles()
        return TproxyRuntimeConfig(
            xrayCorePath = resourceFilePaths.xrayCorePath,
            pidPath = runtimeFiles.pidPath,
        )
    }

    fun create(context: Context, request: ProxyEngineStartRequest): TproxyStartConfig {
        val appState = request.appState
        val resourceFilePaths = context.prepareXrayResourceFilePaths()
        val coreLogPaths = context.prepareXrayCoreLogPaths()
        val tproxyPort = appState.tproxyPortValue()
        val outboundPlan = appState.buildXrayOutboundPlan(request.selectedServer)
        val dnsHosts = appState.xrayDnsHosts(outboundPlan.dnsHostServers)
        val runtimeFiles = resourceFilePaths.toTproxyRuntimeFiles()

        return TproxyStartConfig(
            xrayConfigJson = XrayConfigFactory.buildXrayConfig(
                XrayConfigRequest(
                    appState = appState,
                    selectedServer = request.selectedServer,
                    inbounds = appState.buildTproxyInbounds(tproxyPort),
                    coreLogPaths = coreLogPaths,
                    dnsHosts = dnsHosts,
                    dnsHijackInboundTags = listOf(XrayTags.TPROXY_INBOUND),
                ),
            ),
            setuidgidPath = resourceFilePaths.setuidgidPath,
            xrayCorePath = resourceFilePaths.xrayCorePath,
            dataDir = resourceFilePaths.dataDir,
            configPath = runtimeFiles.configPath,
            pidPath = runtimeFiles.pidPath,
            tproxyPort = tproxyPort,
            enableIpv6 = appState.enableIpv6,
            enableAccessLog = appState.enableAccessLog,
            coreLogPaths = coreLogPaths,
            iptablesConfig = appState.toTproxyIptablesConfig(context),
        )
    }
}

private data class TproxyRuntimeFiles(
    val configPath: String,
    val pidPath: String,
)

private fun XrayResourceFilePaths.toTproxyRuntimeFiles(): TproxyRuntimeFiles {
    val dir = File(dataDir)
    return TproxyRuntimeFiles(
        configPath = File(dir, TproxyConfigFileName).absolutePath,
        pidPath = File(dir, TproxyPidFileName).absolutePath,
    )
}

private fun AppState.buildTproxyInbounds(tproxyPort: Int): List<JSONObject> {
    return buildList {
        add(buildTproxyTunnelInbound(this@buildTproxyInbounds, tproxyPort))
        socks5ProxyPort.portValue()
            ?.takeIf { enableSocks5Proxy }
            ?.let { port -> add(buildTproxySocksInbound(port)) }
        httpProxyPort.portValue()
            ?.takeIf { enableHttpProxy }
            ?.let { port -> add(buildTproxyHttpInbound(port)) }
    }
}

private fun buildTproxyTunnelInbound(
    appState: AppState,
    port: Int,
): JSONObject {
    return JSONObject()
        .put("tag", XrayTags.TPROXY_INBOUND)
        .put("port", port)
        .put("protocol", XrayProtocols.DOKODEMO_DOOR)
        .put(
            "settings",
            JSONObject()
                .put("allowedNetwork", "tcp,udp")
                .put("followRedirect", true)
                .put("userLevel", 0),
        )
        .put(
            "streamSettings",
            JSONObject()
                .put(
                    "sockopt",
                    JSONObject()
                        .put("tproxy", "tproxy"),
                ),
        )
        .apply {
            if (appState.enableSniffing) {
                put(
                    "sniffing",
                    JSONObject()
                        .put("enabled", true)
                        .put("destOverride", xraySniffingDestOverrides(appState.effectiveFakeDnsEnabled).toJsonStringArray())
                        .put("routeOnly", appState.enableSniffingRouteOnly),
                )
            }
        }
}

private fun buildTproxySocksInbound(port: Int): JSONObject {
    return JSONObject()
        .put("tag", XrayTags.TPROXY_SOCKS_INBOUND)
        .put("listen", TproxyShareListenAddress)
        .put("port", port)
        .put("protocol", XrayProtocols.SOCKS)
        .put(
            "settings",
            JSONObject()
                .put("auth", "noauth")
                .put("udp", true)
                .put("ip", TproxyShareListenAddress)
                .put("userLevel", 0),
        )
}

private fun buildTproxyHttpInbound(port: Int): JSONObject {
    return JSONObject()
        .put("tag", XrayTags.TPROXY_HTTP_INBOUND)
        .put("listen", TproxyShareListenAddress)
        .put("port", port)
        .put("protocol", XrayProtocols.HTTP)
        .put(
            "settings",
            JSONObject()
                .put("allowTransparent", false)
                .put("userLevel", 0),
        )
}

private fun AppState.toTproxyIptablesConfig(context: Context): TproxyIptablesConfig {
    val localInterfaceCidrs = collectTproxyLocalInterfaceCidrs().toSanitizedCidrs()
    val proxyPrivateCidrs = privateAddressCidrs.toSanitizedCidrs()
    val bypassPrivateCidrs = TproxyBuiltinPrivateCidrs.toSanitizedCidrs()
    val selectedAppKeys = proxyAppListSelectedApps.map(String::trim).filter(String::isNotEmpty)
    val appListMode = if (selectedAppKeys.isEmpty()) {
        ProxyAppListModeGlobal
    } else {
        proxyAppListMode.toTproxyAppListMode()
    }
    return TproxyIptablesConfig(
        externalInterfacePrefixes = externalInterfaces.map(String::trim).filter(String::isNotEmpty).distinct(),
        ignoredInterfaces = ignoredInterfaces.map(String::trim).filter(String::isNotEmpty).distinct(),
        localInterfaceIpv4Cidrs = localInterfaceCidrs.filterNot { cidr -> ":" in cidr },
        localInterfaceIpv6Cidrs = localInterfaceCidrs.filter { cidr -> ":" in cidr },
        proxyPrivateIpv4Cidrs = proxyPrivateCidrs.filterNot { cidr -> ":" in cidr },
        proxyPrivateIpv6Cidrs = proxyPrivateCidrs.filter { cidr -> ":" in cidr },
        bypassPrivateIpv4Cidrs = bypassPrivateCidrs.filterNot { cidr -> ":" in cidr },
        bypassPrivateIpv6Cidrs = bypassPrivateCidrs.filter { cidr -> ":" in cidr },
        proxyAppListMode = appListMode,
        proxyApplicationUids = if (appListMode == ProxyAppListModeGlobal) {
            emptyList()
        } else {
            context.resolveProxyApplicationUids(selectedAppKeys)
        },
    )
}

private fun Int.toTproxyAppListMode(): Int {
    return when (this) {
        ProxyAppListModeBlacklist,
        ProxyAppListModeWhitelist,
        ProxyAppListModeGlobal -> this

        else -> ProxyAppListModeGlobal
    }
}

private fun Context.resolveProxyApplicationUids(packageKeys: List<String>): List<Int> {
    val defaultUserId = Process.myUid().toAndroidUserId()
    val appIds = mutableMapOf<String, Int?>()
    return packageKeys.mapNotNull { key ->
        val packageKey = key.toProxyAppPackageKey(defaultUserId) ?: return@mapNotNull null
        val appId = appIds.getOrPut(packageKey.packageName) {
            runCatching {
                packageManager.getApplicationInfoCompat(packageKey.packageName).uid.toAndroidAppId()
            }.getOrNull()
        } ?: return@mapNotNull null
        packageKey.userId * ANDROID_USER_UID_RANGE + appId
    }.distinct()
}

private data class ProxyAppPackageKey(
    val userId: Int,
    val packageName: String,
)

private fun String.toProxyAppPackageKey(defaultUserId: Int): ProxyAppPackageKey? {
    val normalized = trim().takeIf(String::isNotEmpty) ?: return null
    val separatorIndex = normalized.indexOf(':')
    if (separatorIndex < 0) {
        return ProxyAppPackageKey(
            userId = defaultUserId,
            packageName = normalized,
        )
    }
    val packageName = normalized.substring(separatorIndex + 1).trim().takeIf(String::isNotEmpty) ?: return null
    return ProxyAppPackageKey(
        userId = normalized.substring(0, separatorIndex).toIntOrNull() ?: return null,
        packageName = packageName,
    )
}

private fun List<String>.toSanitizedCidrs(): List<String> {
    return map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
}

private fun AppState.tproxyPortValue(): Int {
    return transparentProxyPort.portValue() ?: DefaultTproxyPort
}

private fun String.portValue(): Int? {
    return toIntOrNull()?.takeIf { port -> port in NetworkLimits.PORT_MIN..NetworkLimits.PORT_MAX }
}
