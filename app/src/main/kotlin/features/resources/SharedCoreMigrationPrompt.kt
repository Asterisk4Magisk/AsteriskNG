// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.LocalAppServices
import app.LocalAppStateStore
import app.R
import app.modes.isRootRunMode
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ui.components.WarningConfirmDialog
import androidx.compose.ui.res.stringResource

private object SharedCoreMigrationState {
    var checked = false
    var show by mutableStateOf(false)
    var busy by mutableStateOf(false)
}

@Composable
internal fun SharedCoreMigrationPrompt() {
    val context = LocalContext.current.applicationContext
    val services = LocalAppServices.current
    val stateStore = LocalAppStateStore.current
    val state by stateStore.state.collectAsState()
    // Installation-local migration marker; never included in app settings exports/backups.
    val marker = remember(context) { File(context.noBackupFilesDir, "shared-xray-core-migration-v1") }
    val migration = SharedCoreMigrationState

    suspend fun finishMigration() {
        withContext(Dispatchers.IO) {
            check(marker.exists() || marker.createNewFile()) { "Failed to save Core migration choice" }
        }
        migration.show = false
    }

    LaunchedEffect(marker, services.resourceFileUseCase) {
        if (migration.checked) return@LaunchedEffect
        try {
            if (!withContext(Dispatchers.IO) { marker.exists() }) {
                if (services.resourceFileUseCase.hasCustomXrayCore()) migration.show = true
                else finishMigration()
            }
            migration.checked = true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            services.tipNotifier.showError(error)
        }
    }

    fun choose(remove: Boolean) {
        if (migration.busy) return
        migration.busy = true
        services.appScope.launch {
            try {
                if (remove) {
                    services.restoreSharedXrayCore(
                        state = stateStore.state.value,
                        onRootStopped = { stateStore.update { it.copy(proxyRunning = false) } },
                    )
                }
                finishMigration()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                services.tipNotifier.showError(error)
            } finally {
                migration.busy = false
            }
        }
    }

    WarningConfirmDialog(
        show = migration.show,
        title = stringResource(R.string.shared_core_migration_title),
        summary = stringResource(R.string.shared_core_migration_message) +
            if (state.runMode.isRootRunMode()) {
                "\n" + stringResource(R.string.shared_core_migration_stop_root)
            } else "",
        dismissText = stringResource(R.string.shared_core_migration_keep),
        confirmText = stringResource(R.string.shared_core_migration_remove),
        onDismissRequest = { choose(remove = false) },
        onConfirm = { choose(remove = true) },
    )
}
