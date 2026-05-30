// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.proxy

import app.AppState
import app.ProxyServerState

data class ProxyEngineStartRequest(
    val appState: AppState,
    val selectedServer: ProxyServerState,
)

data class ProxyEngineStatus(
    val running: Boolean,
    val runMode: Int? = null,
)
