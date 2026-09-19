// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import app.AppServices
import app.AppState
import app.ResourceFileKind
import app.ResourceFilesStatus
import app.modes.isRootRunMode
import features.settings.usecase.RootBootScriptResult

internal suspend fun AppServices.restoreSharedXrayCore(
    state: AppState,
    onRootStopped: () -> Unit,
): ResourceFilesStatus = proxyEngine.changeRootCore(state.runMode, onRootStopped) {
    val status = resourceFileUseCase.restoreBundled(ResourceFileKind.XrayCore)
    refreshXrayCoreBoot(state.copy(proxyRunning = false))
    status
}

internal suspend fun AppServices.refreshXrayCoreBoot(state: AppState) {
    if (state.runMode.isRootRunMode() && state.enableRootBootScript) {
        when (val result = rootBootScriptUseCase.refresh(state)) {
            RootBootScriptResult.Success -> Unit
            is RootBootScriptResult.Failed -> throw result.error
            else -> error("Failed to refresh ROOT boot configuration after changing Core: $result")
        }
    }
}
