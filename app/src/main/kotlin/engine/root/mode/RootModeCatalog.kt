// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.mode

import app.R
import app.modes.RunModeBpf2Socks
import app.modes.RunModeTproxy
import app.modes.RunModeTun2Socks
import engine.root.daemon.config.AsteriskdMode
import engine.root.config.RootConfigBuildContext

internal object RootModeCatalog {
    val definitions: List<RootModeDefinition> = validatedDefinitions(
        RootModeDefinition(
            runMode = RunModeTproxy,
            daemonMode = AsteriskdMode.Tproxy,
            rootRequiredErrorResId = R.string.error_tproxy_root_required,
            startFailedErrorResId = R.string.error_tproxy_start_failed,
            buildConfig = RootConfigBuildContext::buildTproxyStartConfig,
        ),
        RootModeDefinition(
            runMode = RunModeTun2Socks,
            daemonMode = AsteriskdMode.Tun2Socks,
            rootRequiredErrorResId = R.string.error_tun2socks_root_required,
            startFailedErrorResId = R.string.error_tun2socks_start_failed,
            buildConfig = RootConfigBuildContext::buildTun2SocksStartConfig,
        ),
        RootModeDefinition(
            runMode = RunModeBpf2Socks,
            daemonMode = AsteriskdMode.Bpf2Socks,
            rootRequiredErrorResId = R.string.error_bpf2socks_root_required,
            startFailedErrorResId = R.string.error_bpf2socks_start_failed,
            buildConfig = RootConfigBuildContext::buildBpf2SocksStartConfig,
        ),
    )

    private val byRunMode = definitions.associateBy(RootModeDefinition::runMode)

    fun find(runMode: Int): RootModeDefinition? = byRunMode[runMode]

    fun require(runMode: Int): RootModeDefinition {
        return requireNotNull(find(runMode)) { "Unsupported ROOT run mode: $runMode" }
    }
}

private fun validatedDefinitions(vararg values: RootModeDefinition): List<RootModeDefinition> {
    val definitions = values.toList()
    require(definitions.map(RootModeDefinition::runMode).distinct().size == definitions.size) {
        "Duplicate ROOT run mode"
    }
    require(definitions.map(RootModeDefinition::daemonMode).distinct().size == definitions.size) {
        "Duplicate asteriskd mode"
    }
    return definitions
}
