// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.proxy

import android.content.Context
import android.content.Intent
import app.AppState
import app.ProxyServerState
import app.displayTitle
import engine.root.runtime.model.RootRuntimeOwner
import engine.root.runtime.model.RootRuntimeSnapshot
import engine.stats.ProxyTrafficStatsRuntime
import engine.stats.ProxyTrafficStatsRuntimeStore
import engine.stats.XrayStatsApiListenAddress
import engine.stats.resolveXrayStatsApiPort
import engine.stats.xrayStatsApiExcludedPorts
import engine.xray.XrayStatsApiConfig
import engine.xray.xrayStatsApiTag

/** Asks the user for VPN consent; returns whether it was granted. */
typealias VpnPermissionRequester = suspend (Intent) -> Boolean

/** Used by entry points that cannot show the system VPN consent dialog. */
val DenyVpnPermission: VpnPermissionRequester = { false }

data class ProxyEngineStartRequest(
    val appState: AppState,
    val selectedServer: ProxyServerState,
    val xrayStatsApiListenAddress: String? = null,
    val xrayStatsApiPort: Int? = null,
)

data class ProxyEngineStatus(
    val running: Boolean,
    val runMode: Int? = null,
    val appState: AppState? = null,
    val rootSnapshot: RootRuntimeSnapshot? = null,
) {
    companion object {
        fun fromRootSnapshot(
            localOwner: RootRuntimeOwner,
            runMode: Int,
            snapshot: RootRuntimeSnapshot,
        ): ProxyEngineStatus = ProxyEngineStatus(
            running = snapshot.owner == localOwner && snapshot.running,
            runMode = runMode,
            rootSnapshot = snapshot,
        )
    }
}

internal fun ProxyEngineStartRequest.xrayStatsApiConfig(): XrayStatsApiConfig? {
    val listenAddress = xrayStatsApiListenAddress?.takeIf(String::isNotBlank) ?: return null
    val port = xrayStatsApiPort?.takeIf { value -> value > 0 } ?: return null
    return XrayStatsApiConfig(
        listenAddress = listenAddress,
        port = port,
        apiTag = selectedServer.server.xrayStatsApiTag(),
    )
}

/**
 * Adds the stats API inbound to a configuration that is about to be published. The port is
 * persisted as soon as it is allocated, so the running core and the ROOT boot script always
 * share it; see [resolveXrayStatsApiPort] for when [verifyAvailability] must be off.
 */
internal fun ProxyEngineStartRequest.withTrafficStatsConfig(
    context: Context,
    verifyAvailability: Boolean,
): Pair<ProxyEngineStartRequest, ProxyTrafficStatsRuntime?> {
    if (!appState.enableTrafficStatsNotification) {
        return this to null
    }
    val storedPort = ProxyTrafficStatsRuntimeStore.readPort(context)
    val port = resolveXrayStatsApiPort(
        preferredPort = storedPort,
        excludedPorts = appState.xrayStatsApiExcludedPorts(),
        verifyAvailability = verifyAvailability,
    )
    if (port != storedPort) {
        ProxyTrafficStatsRuntimeStore.writePort(context, port)
    }
    val request = copy(
        xrayStatsApiListenAddress = XrayStatsApiListenAddress,
        xrayStatsApiPort = port,
    )
    val statsApiConfig = checkNotNull(request.xrayStatsApiConfig())
    val runtime = ProxyTrafficStatsRuntime(
        listenAddress = statsApiConfig.listenAddress,
        port = statsApiConfig.port,
        serverName = selectedServer.displayTitle(),
        apiTag = statsApiConfig.apiTag,
    )
    return request to runtime
}
