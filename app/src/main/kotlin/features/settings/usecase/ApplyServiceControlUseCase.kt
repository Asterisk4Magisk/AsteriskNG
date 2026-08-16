// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.usecase

import app.AppState
import app.ServiceControlSettings
import engine.proxy.AndroidProxyEngine
import features.settings.servicecontrol.normalizeServiceControlSettings

internal class ApplyServiceControlUseCase(
    private val proxyEngine: AndroidProxyEngine,
) {
    suspend fun apply(currentState: AppState, draft: ServiceControlSettings): AppState {
        val nextState = currentState.copy(
            serviceControl = normalizeServiceControlSettings(draft),
        )
        return proxyEngine.reconfigureServiceControl(nextState)
    }
}

