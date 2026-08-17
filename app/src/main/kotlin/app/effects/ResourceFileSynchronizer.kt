// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.AppState
import data.AndroidAppStateStore
import features.logs.AndroidAppLogger
import features.resources.ResourceFileUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlin.coroutines.cancellation.CancellationException

@Composable
internal fun ResourceFileSynchronizer(
    resourceFileUseCase: ResourceFileUseCase,
    stateStore: AndroidAppStateStore,
) {
    LaunchedEffect(resourceFileUseCase, stateStore) {
        stateStore.state.collectResourceSynchronizationStates { state ->
            try {
                resourceFileUseCase.synchronizeBundledFilesAfterPackageUpdate(
                    resourceFileSource = state.resourceFileSource,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                AndroidAppLogger.warn(
                    LogTag,
                    "Failed to synchronize bundled resource files",
                    error,
                )
            }
        }
    }
}

internal suspend fun Flow<AppState>.collectResourceSynchronizationStates(
    synchronize: suspend (AppState) -> Unit,
) {
    distinctUntilChangedBy(AppState::runMode).collect(synchronize)
}

private const val LogTag = "ResourceFileSync"
