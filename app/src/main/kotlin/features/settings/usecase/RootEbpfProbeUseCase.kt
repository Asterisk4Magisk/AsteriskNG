// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.usecase

import android.content.Context
import app.AppState
import engine.root.parseRootEbpfProbeResult
import engine.root.prepareRootRuntimeLayout
import system.AndroidRootShellGateway
import system.ShellExecOptions
import utils.shellQuote

internal class RootEbpfProbeUseCase(
    context: Context,
    private val rootAccess: AndroidRootShellGateway,
) {
    private val appContext = context.applicationContext

    suspend fun probe(state: AppState): RootEbpfProbeResult {
        if (!rootAccess.hasRootAccess()) {
            return RootEbpfProbeResult.RootUnavailable
        }
        return runCatching {
            val runtimeLayout = appContext.prepareRootRuntimeLayout()
            val probeCommand = listOf(
                runtimeLayout.bpfMatcherPath.shellQuote(),
                "--probe",
                "--json",
                "--ipv6",
                if (state.enableIpv6) "1" else "0",
            ).joinToString(" ")
            val command = buildString {
                append(
                    $$"""
                    if [ ! -f $${runtimeLayout.bpfMatcherPath.shellQuote()} ]; then
                        echo '{"supported":false,"message":"eBPF matcher is unavailable","checks":[]}'
                        exit 0
                    fi
                    chmod 755 $${runtimeLayout.bpfMatcherPath.shellQuote()}
                    $${probeCommand}
                    """.trimIndent(),
                )
            }
            val shellResult = rootAccess.exec(command, ShellExecOptions(logFailure = false))
            val probeResult = parseRootEbpfProbeResult(shellResult.stdout.ifBlank { shellResult.stderr })
            if (probeResult.supported) {
                RootEbpfProbeResult.Success(probeResult)
            } else {
                RootEbpfProbeResult.Unsupported(probeResult)
            }
        }.getOrElse { error ->
            RootEbpfProbeResult.Failed(error)
        }
    }
}

internal sealed interface RootEbpfProbeResult {
    data class Success(val probe: engine.root.RootEbpfProbeResult) : RootEbpfProbeResult
    data class Unsupported(val probe: engine.root.RootEbpfProbeResult) : RootEbpfProbeResult
    data object RootUnavailable : RootEbpfProbeResult
    data class Failed(val error: Throwable) : RootEbpfProbeResult
}
