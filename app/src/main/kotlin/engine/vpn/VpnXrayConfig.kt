package engine.vpn

import android.content.Context
import android.os.Process
import app.effectiveLocalDnsEnabled
import engine.xray.XrayConfigFactory
import engine.xray.XrayConfigRequest
import engine.xray.XrayCoreLogPaths
import engine.xray.buildXrayOutboundPlan
import engine.xray.prepareXrayCoreLogPaths
import features.resources.runtime.prepareXrayResourceFilePaths
import system.toAndroidUserId
import engine.proxy.ProxyEngineStartRequest

internal data class VpnServiceStartConfig(
    val sessionName: String,
    val mtu: Int = VpnDefaults.MTU,
    val ipv4Address: String = defaultIpv4TunAddress.address,
    val ipv4PrefixLength: Int = defaultIpv4TunAddress.prefixLength,
    val ipv6Address: String? = null,
    val ipv6PrefixLength: Int = defaultIpv6TunAddress.prefixLength,
    val enableIpv6: Boolean = false,
    val enableLocalDns: Boolean = true,
    val dnsServers: List<String>,
    val xrayConfigJson: String,
    val applicationPolicy: VpnApplicationPolicy,
    val localProxyOptions: VpnLocalProxyOptions,
    val appendHttpProxyOptions: VpnAppendHttpProxyOptions,
    val coreLogPaths: XrayCoreLogPaths,
    val enableAccessLog: Boolean = false,
    val dataDir: String = "",
)

internal object VpnXrayConfigFactory {
    fun create(context: Context, request: ProxyEngineStartRequest): VpnServiceStartConfig {
        val appState = request.appState
        val coreLogPaths = context.prepareXrayCoreLogPaths()
        val resourceFilePaths = context.prepareXrayResourceFilePaths()
        val tunOptions = appState.toVpnTunOptions()
        val localProxyOptions = appState.toVpnLocalProxyOptions()
        val appendHttpProxyOptions = appState.toVpnAppendHttpProxyOptions(localProxyOptions)
        val outboundPlan = appState.buildXrayOutboundPlan(request.selectedServer)
        val dnsHosts = appState.xrayDnsHosts(outboundPlan.dnsHostServers)

        return VpnServiceStartConfig(
            sessionName = "AsteriskNG",
            mtu = tunOptions.mtu,
            ipv4Address = tunOptions.ipv4Address.address,
            ipv4PrefixLength = tunOptions.ipv4Address.prefixLength,
            enableIpv6 = appState.enableIpv6,
            ipv6Address = if (appState.enableIpv6) tunOptions.ipv6Address.address else null,
            ipv6PrefixLength = tunOptions.ipv6Address.prefixLength,
            enableLocalDns = appState.effectiveLocalDnsEnabled,
            dnsServers = tunOptions.dnsServers,
            xrayConfigJson = XrayConfigFactory.buildXrayConfig(
                XrayConfigRequest(
                    appState = appState,
                    selectedServer = request.selectedServer,
                    inbounds = buildList {
                        add(buildVpnLocalSocksInbound(appState, localProxyOptions))
                        if (appendHttpProxyOptions.enabled) {
                            add(buildVpnAppendHttpInbound(appendHttpProxyOptions))
                        }
                        add(buildVpnTunInbound(appState, tunOptions))
                    },
                    coreLogPaths = coreLogPaths,
                    dnsHosts = dnsHosts,
                ),
            ),
            applicationPolicy = appState.toVpnApplicationPolicy(Process.myUid().toAndroidUserId()),
            localProxyOptions = localProxyOptions,
            appendHttpProxyOptions = appendHttpProxyOptions,
            coreLogPaths = coreLogPaths,
            enableAccessLog = appState.enableAccessLog,
            dataDir = resourceFilePaths.dataDir,
        )
    }
}
