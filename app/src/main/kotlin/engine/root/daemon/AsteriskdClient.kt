// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.daemon

import engine.root.daemon.control.AsteriskdControlCodec
import engine.root.daemon.control.AsteriskdControlResponse
import features.logs.AndroidAppLogger
import system.RootShellGateway
import system.ShellExecOptions
import utils.shellQuote

internal class AsteriskdClient(
    private val shell: RootShellGateway,
) {
    suspend fun status(executablePath: String): AsteriskdControlResponse = runControl(executablePath, "status")

    suspend fun stop(executablePath: String): AsteriskdControlResponse = runControl(executablePath, "stop")

    private suspend fun runControl(
        executablePath: String,
        requestId: String,
    ): AsteriskdControlResponse {
        val command = "${executablePath.shellQuote()} $requestId"
        val result = shell.exec(command, ShellExecOptions(logFailure = false))
        return runCatching {
            AsteriskdControlCodec.decodeShellResponse(requestId, result)
        }.getOrElse { error ->
            AndroidAppLogger.error(
                LogTag,
                "invalid_control_response request=$requestId errno=${result.errno} " +
                    "stdout=${result.stdout} stderr=${result.stderr}",
                error,
            )
            throw error
        }
    }

    private companion object {
        const val LogTag = "AsteriskdClient"
    }
}
