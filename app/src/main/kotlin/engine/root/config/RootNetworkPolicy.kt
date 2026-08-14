// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.config

import android.content.Context
import android.os.Process
import app.AppState
import app.modes.ProxyAppListModeGlobal
import utils.toTrimmedNonEmptyDistinctList

private val RootDefaultBypassPrivateCidrs = listOf(
    "0.0.0.0/8", "10.0.0.0/8", "100.0.0.0/8", "127.0.0.0/8", "169.254.0.0/16",
    "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16",
    "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4",
    "255.255.255.255/32", "::/128", "::1/128", "::ffff:0:0/96", "100::/64",
    "64:ff9b::/96", "2001::/32", "2001:10::/28", "2001:20::/28", "2001:db8::/32",
    "2002::/16", "fe80::/10", "ff00::/8",
)

internal data class RootIptablesConfig(
    val enableEbpfRules: Boolean = false,
    val enableEbpfDirectCidrBypass: Boolean = false,
    val externalInterfacePrefixes: List<String> = emptyList(),
    val ignoredInterfaces: List<String> = emptyList(),
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
): RootIptablesConfig {
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
