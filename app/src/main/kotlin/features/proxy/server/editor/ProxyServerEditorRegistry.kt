package features.proxy.server.editor

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import app.R
import features.proxy.server.model.ChainProxy
import features.proxy.server.model.HTTP
import features.proxy.server.model.Hysteria2
import features.proxy.server.model.ProxyServer
import features.proxy.server.model.Shadowsocks
import features.proxy.server.model.Socks
import features.proxy.server.model.StrategyGroup
import features.proxy.server.model.Trojan
import features.proxy.server.model.VLESS
import features.proxy.server.model.VMess
import features.proxy.server.model.Wireguard
import androidx.compose.ui.res.stringResource

internal data class ProxyServerEditorOptions(
    val groupOptions: List<ProxyServerEditorGroupOption>,
    val memberOptions: List<ProxyServerEditorMemberOption>,
)

internal fun ProxyServer<*>.editableCopy(): ProxyServer<*> {
    return when (this) {
        is StrategyGroup -> copy()
        is ChainProxy -> copy()
        is HTTP -> copy()
        is Socks -> copy()
        is Shadowsocks -> copy()
        is VMess -> copy()
        is Trojan -> copy()
        is VLESS -> copy()
        is Wireguard -> copy()
        is Hysteria2 -> copy()
        else -> unsupportedProxyServerEditor()
    }
}

@Composable
internal fun ProxyServer<*>.editorTitle(): String {
    return when (this) {
        is StrategyGroup -> stringResource(R.string.proxy_editor_strategy_group_title)
        is ChainProxy -> stringResource(R.string.proxy_editor_chain_proxy_title)
        else -> getInfo().protocol
    }
}

internal fun LazyListScope.proxyServerEditorContent(
    proxyServer: ProxyServer<*>,
    options: ProxyServerEditorOptions,
) {
    when (proxyServer) {
        is StrategyGroup -> strategyGroupProxyServer(proxyServer, options.groupOptions)
        is ChainProxy -> chainProxyServer(proxyServer, options.memberOptions)
        is HTTP -> httpProxyServer(proxyServer)
        is Socks -> socksProxyServer(proxyServer)
        is Shadowsocks -> shadowsocksProxyServer(proxyServer)
        is VMess -> vmessProxyServer(proxyServer)
        is Trojan -> trojanProxyServer(proxyServer)
        is VLESS -> vlessProxyServer(proxyServer)
        is Wireguard -> wireguardProxyServer(proxyServer)
        is Hysteria2 -> hysteria2ProxyServer(proxyServer)
        else -> proxyServer.unsupportedProxyServerEditor()
    }
}

private fun ProxyServer<*>.unsupportedProxyServerEditor(): Nothing {
    error("Unsupported proxy server editor: ${this::class.simpleName}")
}
