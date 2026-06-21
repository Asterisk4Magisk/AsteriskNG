// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.list

internal enum class ProxyServerListAddAction {
    ScanQrCode,
    Clipboard,
    File,
    StrategyGroup,
    ChainProxy,
    Custom,
    HTTP,
    VMess,
    VLESS,
    Trojan,
    Shadowsocks,
    Socks,
    Hysteria2,
    Wireguard,
}

internal enum class ProxyServerListToolAction {
    RestartService,
    TestLatency,
    TestRealConnection,
    UpdateSubscriptions,
    SetLayoutSingle,
    SetLayoutDouble,
    SetLayoutMultiple,
    SetSortDefault,
    SetSortName,
    SetSortLatency,
    CopyAllUrls,
    DeleteDuplicateServers,
    DeleteInvalidServers,
    DeleteAllServers,
}

internal enum class ProxyServerListCopyAction {
    QrCode,
    Url,
    FullJson,
}

internal data class ProxyServerListMenuEntry(
    val title: String,
    val action: ProxyServerListAddAction,
)

internal data class ProxyServerListGroupTabUi(
    val id: Int,
    val name: String,
    val serverCount: Int,
)
