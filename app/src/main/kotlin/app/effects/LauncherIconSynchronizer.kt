// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.AppState
import app.MainActivity
import app.modes.ColorModeThemeDark
import app.modes.ColorModeThemeSystem
import data.AndroidAppStateStore
import features.logs.AndroidAppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Composable
internal fun LauncherIconSynchronizer(
    context: Context,
    stateStore: AndroidAppStateStore,
) {
    val appContext = context.applicationContext
    LaunchedEffect(appContext, stateStore) {
        stateStore.state
            .map { state -> state.usesMonetLauncherIcon }
            .distinctUntilChanged()
            .collect { useMonetIcon ->
                runCatching {
                    withContext(Dispatchers.IO) {
                        appContext.setLauncherIcon(useMonetIcon)
                    }
                }.onFailure { error ->
                    AndroidAppLogger.warn(
                        LogTag,
                        "Failed to synchronize launcher icon",
                        error,
                    )
                }
            }
    }
}

private val AppState.usesMonetLauncherIcon: Boolean
    get() = colorMode in ColorModeThemeSystem..ColorModeThemeDark

private fun Context.setLauncherIcon(useMonetIcon: Boolean) {
    val packageManager = packageManager
    val launcherPackageName = MainActivity::class.java.name.substringBeforeLast('.')
    val defaultLauncher = ComponentName(packageName, "$launcherPackageName.DefaultLauncherActivity")
    val monetLauncher = ComponentName(packageName, "$launcherPackageName.MonetLauncherActivity")
    val enabledLauncher = if (useMonetIcon) monetLauncher else defaultLauncher
    val disabledLauncher = if (useMonetIcon) defaultLauncher else monetLauncher

    packageManager.setComponentEnabledSetting(
        enabledLauncher,
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        PackageManager.DONT_KILL_APP,
    )
    packageManager.setComponentEnabledSetting(
        disabledLauncher,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP,
    )
}

private const val LogTag = "LauncherIconSync"
