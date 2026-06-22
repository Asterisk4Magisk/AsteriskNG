// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root

import android.content.Context
import android.os.Process
import app.AppState
import app.modes.ProxyAppListModeGlobal
import utils.toTrimmedNonEmptyDistinctList

internal data class RootIptablesConfig(
    val mark: String,
    val ipv4Table: String,
    val ipv6Table: String,
    val enableEbpfRules: Boolean = false,
    val enableEbpfDirectCidrBypass: Boolean = false,
    val externalInterfacePrefixes: List<String> = emptyList(),
    val ignoredInterfaces: List<String> = emptyList(),
    val localInterfaceIpv4Cidrs: List<String> = emptyList(),
    val localInterfaceIpv6Cidrs: List<String> = emptyList(),
    val proxyPrivateIpv4Cidrs: List<String> = emptyList(),
    val proxyPrivateIpv6Cidrs: List<String> = emptyList(),
    val bypassPrivateIpv4Cidrs: List<String> = emptyList(),
    val bypassPrivateIpv6Cidrs: List<String> = emptyList(),
    val forcedBypassUids: List<Int> = emptyList(),
    val proxyAppListMode: Int = ProxyAppListModeGlobal,
    val proxyApplicationUids: List<Int> = emptyList(),
)

internal fun RootIptablesConfig.withAppSettings(
    context: Context,
    appState: AppState,
    ignoredLocalInterfaceNames: Set<String>,
): RootIptablesConfig {
    val localInterfaceCidrs = collectRootLocalInterfaceCidrs(
        ignoredInterfaceNames = ignoredLocalInterfaceNames,
    ).toTrimmedNonEmptyDistinctList()
    val proxyPrivateCidrs = appState.privateAddressCidrs.toTrimmedNonEmptyDistinctList()
    val bypassPrivateCidrs = RootDefaultBypassPrivateCidrs.toTrimmedNonEmptyDistinctList()
    val selectedAppKeys = appState.proxyAppListSelectedApps.toTrimmedNonEmptyDistinctList()
    val appListMode = if (selectedAppKeys.isEmpty()) {
        ProxyAppListModeGlobal
    } else {
        appState.proxyAppListMode.toRootProxyAppListMode()
    }

    return copy(
        externalInterfacePrefixes = appState.externalInterfaces.toTrimmedNonEmptyDistinctList(),
        ignoredInterfaces = appState.ignoredInterfaces.toTrimmedNonEmptyDistinctList(),
        localInterfaceIpv4Cidrs = localInterfaceCidrs.ipv4Cidrs(),
        localInterfaceIpv6Cidrs = localInterfaceCidrs.ipv6Cidrs(),
        proxyPrivateIpv4Cidrs = proxyPrivateCidrs.ipv4Cidrs(),
        proxyPrivateIpv6Cidrs = proxyPrivateCidrs.ipv6Cidrs(),
        bypassPrivateIpv4Cidrs = bypassPrivateCidrs.ipv4Cidrs(),
        bypassPrivateIpv6Cidrs = bypassPrivateCidrs.ipv6Cidrs(),
        forcedBypassUids = listOf(Process.myUid()),
        proxyAppListMode = appListMode,
        proxyApplicationUids = if (appListMode == ProxyAppListModeGlobal) {
            emptyList()
        } else {
            context.resolveRootProxyApplicationUids(selectedAppKeys)
        },
        enableEbpfRules = appState.enableRootEbpfRules,
        enableEbpfDirectCidrBypass = appState.enableRootEbpfDirectCidrBypass,
    )
}

private fun List<String>.ipv4Cidrs(): List<String> {
    return filterNot { cidr -> ":" in cidr }
}

private fun List<String>.ipv6Cidrs(): List<String> {
    return filter { cidr -> ":" in cidr }
}
