// Copyright 2026, Asterisk4Magisk contributors
// SPDX-License-Identifier: GPL-3.0
package app.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import data.AndroidAppStateStore
import features.enhancement.EnhancementModuleCoordinator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
internal fun EnhancementModuleUpgradeChecker(stateStore: AndroidAppStateStore, coordinator: EnhancementModuleCoordinator) {
    LaunchedEffect(stateStore, coordinator) {
        // Read-only check on eligible process startup also covers an APK upgrade.
        // Changes to the user setting/mode cancel obsolete work. Nothing is installed here.
        stateStore.state.map { it.enableAppListEnhancement to it.runMode }.distinctUntilChanged().collectLatest { signature ->
            coordinator.check(signature.first, signature.second) {
                val current = stateStore.state.value
                current.enableAppListEnhancement == signature.first && current.runMode == signature.second
            }
        }
    }
}
