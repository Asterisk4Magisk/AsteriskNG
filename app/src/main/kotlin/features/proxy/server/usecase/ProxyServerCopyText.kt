package features.proxy.server.usecase

import android.content.Context
import app.AppState
import app.ProxyServerState
import app.modes.RunModeTproxy
import engine.proxy.ProxyEngineStartRequest
import engine.tproxy.TproxyConfigFactory
import engine.vpn.VpnXrayConfigFactory
import features.proxy.server.model.ChainProxy
import features.proxy.server.model.ProxyServer
import features.proxy.server.model.StrategyGroup
import features.proxy.server.model.getCopyTextOrNull
import features.subscription.DefaultSubscriptionGroupId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun ProxyServerState.proxyServerCopyTextOrNull(
    context: Context,
    appState: AppState,
): String? {
    return when (server) {
        is ChainProxy,
        is StrategyGroup -> withContext(Dispatchers.IO) {
            runCatching {
                context.generatedProxyServerXrayConfig(appState, this@proxyServerCopyTextOrNull)
            }.getOrNull()
        }

        else -> runCatching { server.getCopyTextOrNull() }.getOrNull()
    }
}

internal suspend fun ProxyServer<*>.proxyServerCopyTextOrNull(
    context: Context,
    appState: AppState,
    serverId: Int?,
    groupId: Int?,
): String? {
    val copyServer = ProxyServerState(
        id = serverId ?: TemporaryCopyServerId,
        server = this,
        groupId = groupId ?: DefaultSubscriptionGroupId,
    )
    return copyServer.proxyServerCopyTextOrNull(context, appState)
}

private fun Context.generatedProxyServerXrayConfig(
    appState: AppState,
    selectedServer: ProxyServerState,
): String {
    val copyState = appState.withCopyTargetServer(selectedServer)
    val request = ProxyEngineStartRequest(
        appState = copyState,
        selectedServer = selectedServer,
    )
    return when (copyState.runMode) {
        RunModeTproxy -> TproxyConfigFactory.create(applicationContext, request).xrayConfigJson
        else -> VpnXrayConfigFactory.create(applicationContext, request).xrayConfigJson
    }
}

private fun AppState.withCopyTargetServer(target: ProxyServerState): AppState {
    val index = proxyServers.indexOfFirst { server -> server.id == target.id }
    if (index < 0) return this
    return copy(
        proxyServers = proxyServers.toMutableList().also { servers ->
            servers[index] = target
        },
    )
}

private const val TemporaryCopyServerId = -1
