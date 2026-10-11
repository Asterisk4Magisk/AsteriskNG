// Copyright 2026, Asterisk4Magisk contributors
// SPDX-License-Identifier: GPL-3.0
package features.enhancement

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.LocalAppServices
import app.LocalAppStateStore
import app.R
import app.modes.supportsAppListEnhancement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.preference.SwitchPreference
import ui.components.WarningConfirmDialog

@Composable
internal fun enhancementModuleSummary(state: EnhancementModuleState): String = state.messageResource()?.let { stringResource(it) }.orEmpty()

@Composable
private fun enhancementModuleMessages(): (EnhancementModuleState) -> String? {
    val resources = (EnhancementModulePhase.entries.mapNotNull { EnhancementModuleState(it).messageResource() } +
        EnhancementModuleFailure.entries.mapNotNull { EnhancementModuleState(EnhancementModulePhase.Failed, it).messageResource() }).distinct()
    val messages = resources.associateWith { stringResource(it) }
    return { state -> state.messageResource()?.let(messages::getValue) }
}

private data class EnhancementModuleInstallRequest(
    val choiceId: Long,
    val mode: Int,
    val state: EnhancementModuleState,
)

@Composable
internal fun EnhancementModulePreference() {
    val services = LocalAppServices.current
    val store = LocalAppStateStore.current
    val appState by store.state.collectAsState()
    val coordinator = services.enhancementModuleCoordinator
    val moduleState by coordinator.state.collectAsState()
    val message = enhancementModuleMessages()
    var choiceId by remember { mutableLongStateOf(0L) }
    var pendingInstall by remember { mutableStateOf<EnhancementModuleInstallRequest?>(null) }
    LaunchedEffect(appState.runMode) {
        pendingInstall = null
    }
    SwitchPreference(
        title = stringResource(R.string.settings_app_list_enhancement),
        summary = stringResource(R.string.settings_app_list_enhancement_summary),
        checked = appState.enableAppListEnhancement || moduleState.busy,
        onCheckedChange = { enabled ->
            val choice = ++choiceId
            pendingInstall = null
            if (!enabled) {
                coordinator.invalidate()
                store.update { it.copy(enableAppListEnhancement = false) }
            } else {
                val mode = store.state.value.runMode
                services.appScope.launch(Dispatchers.Main.immediate) {
                    val result = coordinator.check(true, mode) {
                        choiceId == choice && store.state.value.runMode == mode
                    }
                    if (choiceId != choice || store.state.value.runMode != mode) return@launch
                    when {
                        result.canEnable -> {
                            store.update { current ->
                                if (current.runMode == mode) current.copy(enableAppListEnhancement = true) else current
                            }
                        }
                        result.phase == EnhancementModulePhase.Missing ||
                            result.phase == EnhancementModulePhase.UpdateAvailable -> {
                            pendingInstall = EnhancementModuleInstallRequest(choice, mode, result)
                        }
                        result.phase != EnhancementModulePhase.Skipped -> message(result)?.let { services.tipNotifier.show(it) }
                    }
                }
            }
        },
    )
    pendingInstall?.let { request ->
        WarningConfirmDialog(
            show = request.mode == appState.runMode && request.choiceId == choiceId,
            title = stringResource(R.string.enhancement_module_title),
            summary = enhancementModuleSummary(request.state),
            dismissText = stringResource(R.string.common_cancel),
            confirmText = stringResource(
                if (request.state.phase == EnhancementModulePhase.UpdateAvailable) {
                    R.string.enhancement_module_update
                } else {
                    R.string.enhancement_module_install_title
                },
            ),
            onDismissRequest = {
                pendingInstall = null
                ++choiceId
                coordinator.invalidate()
            },
            onConfirm = {
                pendingInstall = null
                services.appScope.launch(Dispatchers.Main.immediate) {
                    val result = coordinator.ensureInstalled(request.mode) {
                        choiceId == request.choiceId && store.state.value.runMode == request.mode
                    }
                    if (choiceId != request.choiceId || store.state.value.runMode != request.mode) return@launch
                    if (result.canEnable) {
                        store.update { current ->
                            if (current.runMode == request.mode) current.copy(enableAppListEnhancement = true) else current
                        }
                    }
                    if (coordinator.installProgress.value == null && result.phase != EnhancementModulePhase.Skipped) {
                        message(result)?.let { services.tipNotifier.show(it) }
                    }
                }
            },
        )
    }
}

internal fun EnhancementModuleState.messageResource(): Int? = when (phase) {
    EnhancementModulePhase.Skipped, EnhancementModulePhase.Checking, EnhancementModulePhase.Current -> null
    EnhancementModulePhase.Installing -> R.string.enhancement_module_installing
    EnhancementModulePhase.Missing -> R.string.enhancement_module_missing
    EnhancementModulePhase.UpdateAvailable -> R.string.enhancement_module_update_available
    EnhancementModulePhase.PendingReboot -> R.string.enhancement_module_reboot
    EnhancementModulePhase.Failed -> when (failure) {
        EnhancementModuleFailure.RootDenied -> R.string.enhancement_module_root_denied
        EnhancementModuleFailure.ApkInstallFailed -> R.string.enhancement_module_apk_install_failed
        EnhancementModuleFailure.UnsupportedManager -> R.string.enhancement_module_manager_unsupported
        EnhancementModuleFailure.Timeout -> R.string.enhancement_module_timeout
        EnhancementModuleFailure.InvalidPackage -> R.string.enhancement_module_invalid_package
        EnhancementModuleFailure.InvalidModule -> R.string.enhancement_module_invalid_module
        EnhancementModuleFailure.IncompatibleRuntime -> R.string.enhancement_module_incompatible
        else -> R.string.enhancement_module_failed
    }
}

@Composable
internal fun EnhancementModulePrompt() {
    val services = LocalAppServices.current
    val store = LocalAppStateStore.current
    val appState by store.state.collectAsState()
    val coordinator = services.enhancementModuleCoordinator
    val moduleState by coordinator.state.collectAsState()
    val progress by coordinator.installProgress.collectAsState()
    val message = enhancementModuleMessages()
    var dismissed by remember { mutableStateOf<EnhancementModulePhase?>(null) }
    LaunchedEffect(moduleState.phase) {
        if (moduleState.phase != EnhancementModulePhase.UpdateAvailable && moduleState.phase != EnhancementModulePhase.PendingReboot) dismissed = null
    }
    progress?.let { install ->
        EnhancementModuleInstallDialog(install) {
            if (install.result?.phase == EnhancementModulePhase.PendingReboot) dismissed = EnhancementModulePhase.PendingReboot
            coordinator.dismissInstallProgress()
        }
    }
    val update = moduleState.phase == EnhancementModulePhase.UpdateAvailable
    val reboot = moduleState.phase == EnhancementModulePhase.PendingReboot
    WarningConfirmDialog(
        show = progress == null && appState.enableAppListEnhancement && appState.runMode.supportsAppListEnhancement() &&
            (update || reboot) && dismissed != moduleState.phase,
        title = stringResource(R.string.enhancement_module_title),
        summary = enhancementModuleSummary(moduleState),
        dismissText = stringResource(R.string.common_cancel),
        confirmText = stringResource(if (update) R.string.common_update else R.string.enhancement_module_acknowledge),
        onDismissRequest = { dismissed = moduleState.phase },
        onConfirm = {
            dismissed = moduleState.phase
            if (update) services.appScope.launch(Dispatchers.Main.immediate) {
                val mode = store.state.value.runMode
                val result = coordinator.ensureInstalled(mode) {
                    store.state.value.enableAppListEnhancement && store.state.value.runMode == mode
                }
                if (coordinator.installProgress.value == null && result.phase != EnhancementModulePhase.Skipped) message(result)?.let { services.tipNotifier.show(it) }
            }
        },
    )
}
