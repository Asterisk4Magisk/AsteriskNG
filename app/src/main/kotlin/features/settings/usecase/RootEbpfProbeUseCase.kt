// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.usecase

import android.content.Context
import app.AppState
import engine.root.probe.RootEbpfProbeResult as NativeMatcherProbe
import system.AndroidRootShellGateway

internal class RootEbpfProbeUseCase(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val rootAccess: AndroidRootShellGateway,
) {
    suspend fun probe(@Suppress("UNUSED_PARAMETER") state: AppState): RootEbpfProbeResult {
        if (!rootAccess.hasRootAccess()) return RootEbpfProbeResult.RootUnavailable
        return RootEbpfProbeResult.Success(
            probe = NativeMatcherProbe(
                supported = true,
                message = "Matcher capability is verified by asteriskd during supervised start",
            ),
            selinuxPolicyApplicator = "asteriskd",
        )
    }
}

internal sealed interface RootEbpfProbeResult {
    data class Success(
        val probe: NativeMatcherProbe,
        val selinuxPolicyApplicator: String?,
    ) : RootEbpfProbeResult

    data class Unsupported(val probe: NativeMatcherProbe) : RootEbpfProbeResult
    data object RootUnavailable : RootEbpfProbeResult
    data class Failed(val error: Throwable) : RootEbpfProbeResult
}
