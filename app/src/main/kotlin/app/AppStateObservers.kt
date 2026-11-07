// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Apply [select] of the app state to this activity now, then again on every distinct change
 * while the activity is started.
 */
internal fun <T> ComponentActivity.applyAppStateWhileStarted(
    select: (AppState) -> T,
    apply: (T) -> Unit,
) {
    val state = (application as AsteriskApplication).stateStore.state
    apply(select(state.value))
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            state.map(select)
                .distinctUntilChanged()
                .collect { value -> apply(value) }
        }
    }
}
