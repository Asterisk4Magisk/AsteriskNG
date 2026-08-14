// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.mode

import androidx.annotation.StringRes
import engine.root.daemon.config.AsteriskdMode
import engine.root.config.RootConfigBuildContext
import engine.root.config.RootModeStartConfig

internal data class RootModeDefinition(
    val runMode: Int,
    val daemonMode: AsteriskdMode,
    @StringRes val rootRequiredErrorResId: Int,
    @StringRes val startFailedErrorResId: Int,
    val buildConfig: (RootConfigBuildContext) -> RootModeStartConfig,
)
