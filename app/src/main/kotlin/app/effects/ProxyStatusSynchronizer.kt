// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.AppState
import app.ServiceControlSettings
import app.collectAppState
import app.modes.isRootRunMode
import data.AndroidAppStateStore
import engine.proxy.AndroidProxyEngine
import engine.proxy.ProxyEngineStatus
import engine.stats.ProxyTrafficStatsService
import features.logs.AndroidAppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlin.coroutines.cancellation.CancellationException

@Composable
internal fun ProxyStatusSynchronizer(
    stateStore: AndroidAppStateStore,
    proxyEngine: AndroidProxyEngine,
    updateAppState: ((AppState) -> AppState) -> Unit,
) {
    val appState by stateStore.collectAppState()
    val appContext = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    var foregroundSyncGeneration by remember(stateStore, proxyEngine) { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner, stateStore, proxyEngine) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                foregroundSyncGeneration += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(stateStore, proxyEngine, foregroundSyncGeneration) {
        observeProxyStatus(
            states = stateStore.state,
            readStatus = { snapshot ->
                runCatching { proxyEngine.status(snapshot.runMode, snapshot) }.getOrNull()
            },
            updateAppState = updateAppState,
        )
    }

    LaunchedEffect(lifecycleOwner, stateStore, proxyEngine) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            combine(
                stateStore.state,
                proxyEngine.rootStatusWatchGeneration,
            ) { state, runtimeGeneration ->
                state.rootStatusWatchTarget(runtimeGeneration)
            }
                .distinctUntilChanged()
                .collectLatest { target ->
                    if (target == null) return@collectLatest
                    try {
                        proxyEngine.observeRootStatus(target.runMode).collect { status ->
                            updateAppState { state ->
                                reduceObservedProxyStatus(
                                    currentState = state,
                                    target = target,
                                    status = status,
                                    currentRuntimeGeneration =
                                        proxyEngine.rootStatusWatchGeneration.value,
                                )
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        AndroidAppLogger.warn(
                            ProxyStatusWatchLogTag,
                            "asteriskd status watch failed",
                            error,
                        )
                    }
                    synchronizeProxyStatus(
                        currentState = { stateStore.state.value },
                        readStatus = { snapshot ->
                            runCatching { proxyEngine.status(snapshot.runMode, snapshot) }.getOrNull()
                        },
                        updateAppState = updateAppState,
                    )
                }
        }
    }

    LaunchedEffect(appContext, appState.enableTrafficStatsNotification) {
        if (!appState.enableTrafficStatsNotification) {
            ProxyTrafficStatsService.reconcile(appContext, null)
        }
    }

    LaunchedEffect(appContext, appState.proxyRunning) {
        if (!appState.proxyRunning) {
            ProxyTrafficStatsService.reconcile(appContext, null)
        }
    }
}

internal suspend fun observeProxyStatus(
    states: Flow<AppState>,
    readStatus: suspend (AppState) -> ProxyEngineStatus?,
    updateAppState: (((AppState) -> AppState) -> Unit),
) {
    states
        .distinctUntilChangedBy { state -> state.runMode to state.proxyRunning }
        .collect { snapshot ->
            if (snapshot.rootStatusWatchTarget() != null) return@collect
            synchronizeProxyStatus(
                currentState = { snapshot },
                readStatus = readStatus,
                updateAppState = updateAppState,
            )
        }
}

internal suspend fun synchronizeProxyStatus(
    currentState: () -> AppState,
    readStatus: suspend (AppState) -> ProxyEngineStatus?,
    updateAppState: (((AppState) -> AppState) -> Unit),
): Boolean {
    val snapshot = currentState()
    val shouldCheckRuntime = snapshot.runMode.isRootRunMode() || snapshot.proxyRunning
    if (!shouldCheckRuntime) return true

    val status = readStatus(snapshot) ?: return false
    updateAppState { state ->
        if (state.runMode != snapshot.runMode || state.proxyRunning != snapshot.proxyRunning) {
            return@updateAppState state
        }
        state.withSynchronizedProxyStatus(status)
    }
    return true
}

internal data class RootStatusWatchTarget(
    val runMode: Int,
    val serviceControl: ServiceControlSettings,
    val expectedRunning: Boolean,
    val runtimeGeneration: Long,
)

internal fun AppState.rootStatusWatchTarget(runtimeGeneration: Long = 0L): RootStatusWatchTarget? {
    if (!runMode.isRootRunMode() || (!serviceControl.enabled && !proxyRunning)) return null
    return RootStatusWatchTarget(
        runMode = runMode,
        serviceControl = serviceControl,
        expectedRunning = proxyRunning,
        runtimeGeneration = runtimeGeneration,
    )
}

internal fun reduceObservedProxyStatus(
    currentState: AppState,
    target: RootStatusWatchTarget,
    status: ProxyEngineStatus,
    currentRuntimeGeneration: Long = target.runtimeGeneration,
): AppState {
    if (currentState.rootStatusWatchTarget(currentRuntimeGeneration) != target) return currentState
    return currentState.withSynchronizedProxyStatus(status)
}

private fun AppState.withSynchronizedProxyStatus(status: ProxyEngineStatus): AppState {
    val synchronizedRunMode = status.runMode
        ?.takeIf { status.running && it.isRootRunMode() }
        ?: runMode
    return if (proxyRunning == status.running && runMode == synchronizedRunMode) {
        this
    } else {
        copy(
            runMode = synchronizedRunMode,
            proxyRunning = status.running,
        )
    }
}

private const val ProxyStatusWatchLogTag = "ProxyStatusSynchronizer"
