// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import engine.root.daemon.control.AsteriskdPhase
import engine.root.publication.RootPublicationLaunchMode

internal data class ServiceControlReconfigurePlan(
    val shutdownRequired: Boolean,
    val launchMode: RootPublicationLaunchMode,
)

internal fun serviceControlReconfigurePlan(
    phase: AsteriskdPhase?,
    enabled: Boolean,
): ServiceControlReconfigurePlan =
    when (phase) {
        null -> ServiceControlReconfigurePlan(
            shutdownRequired = false,
            launchMode = if (enabled) {
                RootPublicationLaunchMode.Monitor
            } else {
                RootPublicationLaunchMode.None
            },
        )
        AsteriskdPhase.Running -> ServiceControlReconfigurePlan(
            shutdownRequired = true,
            launchMode = RootPublicationLaunchMode.Service,
        )
        AsteriskdPhase.Stopped -> ServiceControlReconfigurePlan(
            shutdownRequired = true,
            launchMode = if (enabled) {
                RootPublicationLaunchMode.Monitor
            } else {
                RootPublicationLaunchMode.None
            },
        )
        else -> throw IllegalArgumentException("asteriskd must be running or stopped to reconfigure")
    }
