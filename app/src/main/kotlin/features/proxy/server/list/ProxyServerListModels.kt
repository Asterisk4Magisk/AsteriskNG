package features.proxy.server.list

internal enum class ProxyServerListAddAction {
    ScanQrCode,
    Clipboard,
    StrategyGroup,
    ChainProxy,
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
    CopyAllUrls,
    DeleteDuplicateServers,
}

internal data class ProxyServerListMenuEntry(
    val title: String,
    val action: ProxyServerListAddAction,
)

internal data class ProxyServerListToolMenuEntry(
    val title: String,
    val action: ProxyServerListToolAction,
)

internal data class ProxyServerListGroupTabUi(
    val id: Int,
    val name: String,
    val serverCount: Int,
)
