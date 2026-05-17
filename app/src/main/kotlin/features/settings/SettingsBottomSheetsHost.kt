package features.settings

import androidx.compose.runtime.Composable
import app.AppState
import features.settings.sheets.DnsSettingsBottomSheet
import features.settings.sheets.ExternalInterfacesBottomSheet
import features.settings.sheets.FragmentSettingsBottomSheet
import features.settings.sheets.IgnoredInterfacesBottomSheet
import features.settings.sheets.LocalProxySettingsBottomSheet
import features.settings.sheets.MuxSettingsBottomSheet
import features.settings.sheets.PrivateAddressBottomSheet
import features.settings.sheets.ProxySettingsBottomSheet
import features.settings.sheets.VpnSettingsBottomSheet
import features.settings.sheets.orderedBy
import features.settings.sheets.sanitizeExternalInterfaces
import features.settings.sheets.sanitizeFragmentRangeInput
import features.settings.sheets.sanitizeMuxIntegerInput
import features.settings.sheets.sanitizeMuxUdp443Index
import features.settings.sheets.sanitizePrivateAddressCidrs
import app.modes.RunModeTproxy

@Composable
internal fun SettingsBottomSheetsHost(
    appState: AppState,
    sheetState: SettingsSheetState,
    updateAppState: ((AppState) -> AppState) -> Unit,
) {
    ProxySettingsBottomSheet(
        show = sheetState.showProxySettings,
        transparentProxyPort = sheetState.proxySettingsDraft.transparentProxyPort,
        enableSocks5Proxy = sheetState.proxySettingsDraft.enableSocks5Proxy,
        socks5ProxyPort = sheetState.proxySettingsDraft.socks5ProxyPort,
        enableHttpProxy = sheetState.proxySettingsDraft.enableHttpProxy,
        httpProxyPort = sheetState.proxySettingsDraft.httpProxyPort,
        onTransparentProxyPortChange = {
            sheetState.proxySettingsDraft = sheetState.proxySettingsDraft.copy(
                transparentProxyPort = it,
            )
        },
        onEnableSocks5ProxyChange = {
            sheetState.proxySettingsDraft = sheetState.proxySettingsDraft.copy(enableSocks5Proxy = it)
        },
        onSocks5ProxyPortChange = {
            sheetState.proxySettingsDraft = sheetState.proxySettingsDraft.copy(
                socks5ProxyPort = it,
            )
        },
        onEnableHttpProxyChange = {
            sheetState.proxySettingsDraft = sheetState.proxySettingsDraft.copy(enableHttpProxy = it)
        },
        onHttpProxyPortChange = {
            sheetState.proxySettingsDraft = sheetState.proxySettingsDraft.copy(
                httpProxyPort = it,
            )
        },
        onDismissRequest = { sheetState.showProxySettings = false },
        onSave = { transparentProxyPort, enableSocks5Proxy, socks5ProxyPort, enableHttpProxy, httpProxyPort ->
            updateAppState { state ->
                state.copy(
                    transparentProxyPort = transparentProxyPort,
                    enableSocks5Proxy = enableSocks5Proxy,
                    socks5ProxyPort = socks5ProxyPort,
                    enableHttpProxy = enableHttpProxy,
                    httpProxyPort = httpProxyPort,
                )
            }
            sheetState.showProxySettings = false
        },
    )
    LocalProxySettingsBottomSheet(
        show = sheetState.showLocalProxySettings,
        port = sheetState.localProxySettingsDraft.port,
        enableDynamicPort = sheetState.localProxySettingsDraft.enableDynamicPort,
        listenAllInterfaces = sheetState.localProxySettingsDraft.listenAllInterfaces,
        username = sheetState.localProxySettingsDraft.username,
        password = sheetState.localProxySettingsDraft.password,
        onPortChange = {
            sheetState.localProxySettingsDraft = sheetState.localProxySettingsDraft.copy(
                port = it.filter(Char::isDigit).take(5),
            )
        },
        onEnableDynamicPortChange = {
            sheetState.localProxySettingsDraft = sheetState.localProxySettingsDraft.copy(enableDynamicPort = it)
        },
        onListenAllInterfacesChange = {
            sheetState.localProxySettingsDraft = sheetState.localProxySettingsDraft.copy(listenAllInterfaces = it)
        },
        onUsernameChange = {
            sheetState.localProxySettingsDraft = sheetState.localProxySettingsDraft.copy(username = it)
        },
        onPasswordChange = {
            sheetState.localProxySettingsDraft = sheetState.localProxySettingsDraft.copy(password = it)
        },
        onDismissRequest = { sheetState.showLocalProxySettings = false },
        onSave = { port, enableDynamicPort, listenAllInterfaces, username, password ->
            updateAppState { state ->
                state.copy(
                    localProxyPort = port,
                    enableDynamicLocalProxyPort = enableDynamicPort,
                    localProxyListenAllInterfaces = listenAllInterfaces,
                    localProxyUsername = username,
                    localProxyPassword = password,
                )
            }
            sheetState.showLocalProxySettings = false
        },
    )
    VpnSettingsBottomSheet(
        show = sheetState.showVpnSettings,
        mtu = sheetState.vpnSettingsDraft.mtu,
        defaultDns = sheetState.vpnSettingsDraft.defaultDns,
        ipv4Cidr = sheetState.vpnSettingsDraft.ipv4Cidr,
        ipv6Cidr = sheetState.vpnSettingsDraft.ipv6Cidr,
        onMtuChange = {
            sheetState.vpnSettingsDraft = sheetState.vpnSettingsDraft.copy(mtu = it.filter(Char::isDigit).take(5))
        },
        onDefaultDnsChange = { sheetState.vpnSettingsDraft = sheetState.vpnSettingsDraft.copy(defaultDns = it) },
        onIpv4CidrChange = { sheetState.vpnSettingsDraft = sheetState.vpnSettingsDraft.copy(ipv4Cidr = it) },
        onIpv6CidrChange = { sheetState.vpnSettingsDraft = sheetState.vpnSettingsDraft.copy(ipv6Cidr = it) },
        onDismissRequest = { sheetState.showVpnSettings = false },
        onSave = { mtu, defaultDns, ipv4Cidr, ipv6Cidr ->
            updateAppState { state ->
                state.copy(
                    vpnMtu = mtu,
                    vpnDefaultDns = defaultDns,
                    vpnIpv4Cidr = ipv4Cidr,
                    vpnIpv6Cidr = ipv6Cidr,
                )
            }
            sheetState.showVpnSettings = false
        },
    )
    DnsSettingsBottomSheet(
        show = sheetState.showDnsSettings,
        enableVpnLocalDns = sheetState.dnsSettingsDraft.enableVpnLocalDns,
        forceEnableLocalDns = appState.runMode == RunModeTproxy,
        enableFakeDns = sheetState.dnsSettingsDraft.enableFakeDns,
        remoteDns = sheetState.dnsSettingsDraft.remoteDns,
        domesticDns = sheetState.dnsSettingsDraft.domesticDns,
        dnsHosts = sheetState.dnsSettingsDraft.dnsHosts,
        onEnableVpnLocalDnsChange = { enabled ->
            sheetState.dnsSettingsDraft = sheetState.dnsSettingsDraft.copy(
                enableVpnLocalDns = enabled,
                enableFakeDns = if (enabled) sheetState.dnsSettingsDraft.enableFakeDns else false,
            )
        },
        onEnableFakeDnsChange = {
            sheetState.dnsSettingsDraft = sheetState.dnsSettingsDraft.copy(enableFakeDns = it)
        },
        onRemoteDnsChange = { sheetState.dnsSettingsDraft = sheetState.dnsSettingsDraft.copy(remoteDns = it) },
        onDomesticDnsChange = { sheetState.dnsSettingsDraft = sheetState.dnsSettingsDraft.copy(domesticDns = it) },
        onDnsHostsChange = { sheetState.dnsSettingsDraft = sheetState.dnsSettingsDraft.copy(dnsHosts = it) },
        onDismissRequest = { sheetState.showDnsSettings = false },
        onSave = { enableVpnLocalDns, enableFakeDns, remoteDns, domesticDns, dnsHosts ->
            updateAppState { state ->
                state.copy(
                    enableVpnLocalDns = enableVpnLocalDns,
                    enableFakeDns = enableFakeDns,
                    remoteDns = remoteDns,
                    domesticDns = domesticDns,
                    dnsHosts = dnsHosts,
                )
            }
            sheetState.showDnsSettings = false
        },
    )
    MuxSettingsBottomSheet(
        show = sheetState.showMuxSettings,
        enabled = sheetState.muxSettingsDraft.enabled,
        concurrency = sheetState.muxSettingsDraft.concurrency,
        xudpConcurrency = sheetState.muxSettingsDraft.xudpConcurrency,
        xudpProxyUdp443 = sheetState.muxSettingsDraft.xudpProxyUdp443,
        onEnabledChange = { sheetState.muxSettingsDraft = sheetState.muxSettingsDraft.copy(enabled = it) },
        onConcurrencyChange = {
            sheetState.muxSettingsDraft = sheetState.muxSettingsDraft.copy(concurrency = sanitizeMuxIntegerInput(it))
        },
        onXudpConcurrencyChange = {
            sheetState.muxSettingsDraft = sheetState.muxSettingsDraft.copy(xudpConcurrency = sanitizeMuxIntegerInput(it))
        },
        onXudpProxyUdp443Change = {
            sheetState.muxSettingsDraft = sheetState.muxSettingsDraft.copy(xudpProxyUdp443 = sanitizeMuxUdp443Index(it))
        },
        onDismissRequest = { sheetState.showMuxSettings = false },
        onSave = { enabled, concurrency, xudpConcurrency, xudpProxyUdp443 ->
            updateAppState { state ->
                state.copy(
                    enableMux = enabled,
                    muxConcurrency = concurrency,
                    muxXudpConcurrency = xudpConcurrency,
                    muxXudpProxyUdp443 = xudpProxyUdp443,
                )
            }
            sheetState.showMuxSettings = false
        },
    )
    FragmentSettingsBottomSheet(
        show = sheetState.showFragmentSettings,
        enabled = sheetState.fragmentSettingsDraft.enabled,
        packets = sheetState.fragmentSettingsDraft.packets,
        length = sheetState.fragmentSettingsDraft.length,
        interval = sheetState.fragmentSettingsDraft.interval,
        onEnabledChange = {
            sheetState.fragmentSettingsDraft = sheetState.fragmentSettingsDraft.copy(enabled = it)
        },
        onPacketsChange = { sheetState.fragmentSettingsDraft = sheetState.fragmentSettingsDraft.copy(packets = it) },
        onLengthChange = {
            sheetState.fragmentSettingsDraft = sheetState.fragmentSettingsDraft.copy(
                length = sanitizeFragmentRangeInput(it),
            )
        },
        onIntervalChange = {
            sheetState.fragmentSettingsDraft = sheetState.fragmentSettingsDraft.copy(
                interval = sanitizeFragmentRangeInput(it),
            )
        },
        onDismissRequest = { sheetState.showFragmentSettings = false },
        onSave = { enabled, packets, length, interval ->
            updateAppState { state ->
                state.copy(
                    enableFragment = enabled,
                    fragmentPackets = packets,
                    fragmentLength = length,
                    fragmentInterval = interval,
                )
            }
            sheetState.showFragmentSettings = false
        },
    )
    ExternalInterfacesBottomSheet(
        show = sheetState.showExternalInterfaces,
        selectedInterfaces = sheetState.externalInterfacesDraft,
        onSelectedInterfacesChange = { sheetState.externalInterfacesDraft = it.sanitizeExternalInterfaces() },
        onDismissRequest = { sheetState.showExternalInterfaces = false },
        onSave = { interfaces ->
            updateAppState { state -> state.copy(externalInterfaces = interfaces.sanitizeExternalInterfaces()) }
            sheetState.showExternalInterfaces = false
        },
    )
    IgnoredInterfacesBottomSheet(
        show = sheetState.showIgnoredInterfaces,
        interfaces = sheetState.ignoredInterfaceOptions,
        selectedInterfaces = sheetState.ignoredInterfacesDraft,
        loading = sheetState.ignoredInterfacesLoading,
        errorMessage = sheetState.ignoredInterfacesError,
        onSelectedInterfacesChange = {
            sheetState.ignoredInterfacesDraft = it.orderedBy(sheetState.ignoredInterfaceOptions)
        },
        onDismissRequest = { sheetState.closeIgnoredInterfaces() },
        onSave = { interfaces ->
            updateAppState { state ->
                state.copy(ignoredInterfaces = interfaces.orderedBy(sheetState.ignoredInterfaceOptions))
            }
            sheetState.closeIgnoredInterfaces()
        },
    )
    PrivateAddressBottomSheet(
        show = sheetState.showPrivateAddresses,
        selectedCidrs = sheetState.privateAddressCidrsDraft,
        onSelectedCidrsChange = { sheetState.privateAddressCidrsDraft = it.sanitizePrivateAddressCidrs() },
        onDismissRequest = { sheetState.showPrivateAddresses = false },
        onSave = { cidrs ->
            updateAppState { state -> state.copy(privateAddressCidrs = cidrs.sanitizePrivateAddressCidrs()) }
            sheetState.showPrivateAddresses = false
        },
    )
}
