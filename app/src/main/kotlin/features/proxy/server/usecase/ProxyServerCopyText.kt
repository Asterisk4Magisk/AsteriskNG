// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.usecase

import android.content.Context
import app.AppState
import app.ProxyServerState
import app.modes.RunModeTun2Socks
import app.modes.RunModeTproxy
import engine.proxy.ProxyEngineStartRequest
import engine.root.prepareRootConfigBuildContext
import engine.tproxy.buildTproxyStartConfig
import engine.tun2socks.buildTun2SocksStartConfig
import engine.vpn.VpnXrayConfigFactory
import features.proxy.server.model.ChainProxy
import features.proxy.server.model.ProxyServer
import features.proxy.server.model.StrategyGroup
import features.proxy.server.model.getCopyTextOrNull
import features.proxy.server.model.getUrlOrNull
import features.subscription.DefaultSubscriptionGroupId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import utils.formatJsonText

internal sealed interface ProxyServerCopyTextResult {
    data class Success(val text: String) : ProxyServerCopyTextResult
    data object Unsupported : ProxyServerCopyTextResult
    data object InvalidConfig : ProxyServerCopyTextResult
}

internal enum class ProxyServerCopyTextType {
    Url,
    FullJson,
}

internal suspend fun ProxyServerState.proxyServerCopyText(
    context: Context,
    appState: AppState,
): ProxyServerCopyTextResult {
    return when (server) {
        is ChainProxy,
        is StrategyGroup -> withContext(Dispatchers.IO) {
            runCatching {
                context.generatedProxyServerXrayConfig(appState, this@proxyServerCopyText).formatJsonText()
            }.fold(
                onSuccess = { text -> ProxyServerCopyTextResult.Success(text) },
                onFailure = { ProxyServerCopyTextResult.InvalidConfig },
            )
        }

        else -> runCatching { server.getCopyTextOrNull() }.fold(
            onSuccess = { text ->
                if (text == null) ProxyServerCopyTextResult.Unsupported else ProxyServerCopyTextResult.Success(text)
            },
            onFailure = { ProxyServerCopyTextResult.InvalidConfig },
        )
    }
}

internal suspend fun ProxyServerState.proxyServerCopyText(
    context: Context,
    appState: AppState,
    type: ProxyServerCopyTextType,
): ProxyServerCopyTextResult {
    return when (type) {
        ProxyServerCopyTextType.Url -> runCatching { server.getUrlOrNull() }.fold(
            onSuccess = { text ->
                if (text == null) ProxyServerCopyTextResult.Unsupported else ProxyServerCopyTextResult.Success(text)
            },
            onFailure = { ProxyServerCopyTextResult.InvalidConfig },
        )

        ProxyServerCopyTextType.FullJson -> withContext(Dispatchers.IO) {
            runCatching {
                context.generatedProxyServerXrayConfig(appState, this@proxyServerCopyText).formatJsonText()
            }.fold(
                onSuccess = { text -> ProxyServerCopyTextResult.Success(text) },
                onFailure = { ProxyServerCopyTextResult.InvalidConfig },
            )
        }
    }
}

internal suspend fun ProxyServer<*>.proxyServerCopyText(
    context: Context,
    appState: AppState,
    serverId: Int?,
    groupId: Int?,
): ProxyServerCopyTextResult {
    val copyServer = ProxyServerState(
        id = serverId ?: TemporaryCopyServerId,
        server = this,
        groupId = groupId ?: DefaultSubscriptionGroupId,
    )
    return copyServer.proxyServerCopyText(context, appState)
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
        RunModeTproxy,
        RunModeTun2Socks -> generatedRootProxyServerXrayConfig(copyState.runMode, request)

        else -> VpnXrayConfigFactory.create(applicationContext, request).xrayConfigJson
    }
}

private fun Context.generatedRootProxyServerXrayConfig(
    runMode: Int,
    request: ProxyEngineStartRequest,
): String {
    val rootContext = applicationContext.prepareRootConfigBuildContext(request)
    return when (runMode) {
        RunModeTproxy -> rootContext.buildTproxyStartConfig().root.xrayConfigJson
        RunModeTun2Socks -> rootContext.buildTun2SocksStartConfig().root.xrayConfigJson
        else -> error("Unsupported ROOT run mode: $runMode")
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
