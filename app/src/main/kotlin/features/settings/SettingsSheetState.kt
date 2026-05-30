// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import app.AppState
import features.settings.sheets.orderedBy
import features.settings.sheets.outletInterfaceOptions
import features.settings.sheets.sanitizeExternalInterfaces
import features.settings.sheets.sanitizePrivateAddressCidrs
import system.AndroidNetworkInterfaceProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

internal class SettingsSheetState(
    private val updateAppState: ((AppState) -> AppState) -> Unit,
) {
    var showProxySettings by mutableStateOf(false)
    var proxySettingsDraft by mutableStateOf(ProxySettingsDraft())

    var showLocalProxySettings by mutableStateOf(false)
    var localProxySettingsDraft by mutableStateOf(LocalProxySettingsDraft())

    var showVpnSettings by mutableStateOf(false)
    var vpnSettingsDraft by mutableStateOf(VpnSettingsDraft())

    var showDnsSettings by mutableStateOf(false)
    var dnsSettingsDraft by mutableStateOf(DnsSettingsDraft())

    var showMuxSettings by mutableStateOf(false)
    var muxSettingsDraft by mutableStateOf(MuxSettingsDraft())

    var showFragmentSettings by mutableStateOf(false)
    var fragmentSettingsDraft by mutableStateOf(FragmentSettingsDraft())

    var showExternalInterfaces by mutableStateOf(false)
    var externalInterfacesDraft by mutableStateOf(emptyList<String>())

    var showIgnoredInterfaces by mutableStateOf(false)
    var ignoredInterfaceOptions by mutableStateOf(emptyList<String>())
    var ignoredInterfacesDraft by mutableStateOf(emptyList<String>())
    var ignoredInterfacesLoading by mutableStateOf(false)
    var ignoredInterfacesError by mutableStateOf<String?>(null)

    var showPrivateAddresses by mutableStateOf(false)
    var privateAddressCidrsDraft by mutableStateOf(emptyList<String>())

    fun openProxySettings(appState: AppState) {
        proxySettingsDraft = appState.toProxySettingsDraft()
        showProxySettings = true
    }

    fun openLocalProxySettings(appState: AppState) {
        localProxySettingsDraft = appState.toLocalProxySettingsDraft()
        showLocalProxySettings = true
    }

    fun openVpnSettings(appState: AppState) {
        vpnSettingsDraft = appState.toVpnSettingsDraft()
        showVpnSettings = true
    }

    fun openDnsSettings(appState: AppState) {
        dnsSettingsDraft = appState.toDnsSettingsDraft()
        showDnsSettings = true
    }

    fun openMuxSettings(appState: AppState) {
        muxSettingsDraft = appState.toMuxSettingsDraft()
        showMuxSettings = true
    }

    fun openFragmentSettings(appState: AppState) {
        fragmentSettingsDraft = appState.toFragmentSettingsDraft()
        showFragmentSettings = true
    }

    fun openExternalInterfaces(appState: AppState) {
        val sanitizedInterfaces = appState.externalInterfaces.sanitizeExternalInterfaces()
        externalInterfacesDraft = sanitizedInterfaces
        if (sanitizedInterfaces != appState.externalInterfaces) {
            updateAppState { state -> state.copy(externalInterfaces = sanitizedInterfaces) }
        }
        showExternalInterfaces = true
    }

    fun openIgnoredInterfaces(appState: AppState) {
        ignoredInterfaceOptions = emptyList()
        ignoredInterfacesDraft = appState.ignoredInterfaces
        ignoredInterfacesLoading = true
        ignoredInterfacesError = null
        showIgnoredInterfaces = true
    }

    fun closeIgnoredInterfaces() {
        showIgnoredInterfaces = false
        ignoredInterfacesLoading = false
    }

    suspend fun loadIgnoredInterfaces(
        appState: AppState,
        networkInterfaces: AndroidNetworkInterfaceProvider,
        errorDetail: String,
    ) {
        if (!showIgnoredInterfaces) {
            ignoredInterfacesLoading = false
            return
        }

        ignoredInterfacesLoading = true
        ignoredInterfacesError = null
        try {
            val options = outletInterfaceOptions(networkInterfaces.listNetworkInterfaces())
            val prunedSelection = appState.ignoredInterfaces.orderedBy(options)
            ignoredInterfaceOptions = options
            ignoredInterfacesDraft = ignoredInterfacesDraft.orderedBy(options)
            if (prunedSelection != appState.ignoredInterfaces) {
                updateAppState { state ->
                    state.copy(ignoredInterfaces = state.ignoredInterfaces.orderedBy(options))
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            ignoredInterfacesError = errorDetail
        } finally {
            ignoredInterfacesLoading = false
        }
    }

    fun openPrivateAddresses(appState: AppState) {
        val sanitizedCidrs = appState.privateAddressCidrs.sanitizePrivateAddressCidrs()
        privateAddressCidrsDraft = sanitizedCidrs
        if (sanitizedCidrs != appState.privateAddressCidrs) {
            updateAppState { state -> state.copy(privateAddressCidrs = sanitizedCidrs) }
        }
        showPrivateAddresses = true
    }
}

@Composable
internal fun rememberSettingsSheetState(
    updateAppState: ((AppState) -> AppState) -> Unit,
): SettingsSheetState {
    return remember(updateAppState) { SettingsSheetState(updateAppState) }
}
