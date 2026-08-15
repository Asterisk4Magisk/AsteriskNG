// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.daemon

import engine.root.daemon.control.AsteriskdControlCodec
import engine.root.daemon.control.AsteriskdControlResponse
import engine.root.daemon.control.AsteriskdEventType
import engine.root.daemon.control.AsteriskdPhase
import engine.root.daemon.control.AsteriskdResultCode
import engine.root.daemon.control.AsteriskdSnapshot
import features.logs.AndroidAppLogger
import kotlinx.coroutines.delay
import system.RootShellGateway
import system.ShellExecOptions
import utils.shellQuote
import kotlin.time.Duration.Companion.milliseconds

internal class AsteriskdClient(
    private val shell: RootShellGateway,
) {
    suspend fun status(executablePath: String): AsteriskdControlResponse = runControl(executablePath, "status")

    suspend fun stop(executablePath: String): AsteriskdControlResponse = runControl(executablePath, "stop")

    suspend fun awaitRunning(executablePath: String): AsteriskdSnapshot {
        val command =
            "timeout -k 1s ${WatchProcessTimeoutSeconds}s " +
                "${executablePath.shellQuote()} watch --until-running"
        var retryDelayMilliseconds = InitialWatchRetryDelayMilliseconds
        while (true) {
            val stream = RunningWatchStream()
            val result = shell.execStreaming(
                command,
                ShellExecOptions(logFailure = false),
                stream::accept,
            )
            stream.runningSnapshot?.let { return it }
            if (stream.retryWhenUnbound) {
                delay(retryDelayMilliseconds.milliseconds)
                retryDelayMilliseconds = (retryDelayMilliseconds * 2L)
                    .coerceAtMost(MaxWatchRetryDelayMilliseconds)
                continue
            }
            error(
                result.stderr.ifBlank {
                    "asteriskd watch ended before a running or failed event"
                },
            )
        }
    }

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
        const val InitialWatchRetryDelayMilliseconds = 10L
        const val MaxWatchRetryDelayMilliseconds = 250L
        const val WatchProcessTimeoutSeconds = 16L
    }

    private class RunningWatchStream {
        @Volatile
        var runningSnapshot: AsteriskdSnapshot? = null
            private set

        @Volatile
        var retryWhenUnbound: Boolean = false
            private set

        private var initialReceived = false
        private var lastSequence = 0L

        fun accept(line: String) {
            if (!initialReceived) {
                val response = AsteriskdControlCodec.decodeResponse(line)
                require(response.requestId == "watch")
                initialReceived = true
                if (response.result.code == AsteriskdResultCode.NotRunning) {
                    retryWhenUnbound = true
                    return
                }
                check(response.result.code == AsteriskdResultCode.Ok) {
                    response.result.message ?: "asteriskd watch request failed"
                }
                val snapshot = requireNotNull(response.result.snapshot)
                if (snapshot.phase == AsteriskdPhase.Stopped) {
                    retryWhenUnbound = true
                    return
                }
                inspect(snapshot)
                return
            }
            val event = AsteriskdControlCodec.decodeEvent(line)
            require(event.sequence > lastSequence) { "asteriskd watch event sequence regressed" }
            lastSequence = event.sequence
            if (event.type == AsteriskdEventType.Failed ||
                event.snapshot.phase == AsteriskdPhase.Failed
            ) {
                error(
                    event.details?.message
                        ?: event.snapshot.error?.message
                        ?: "asteriskd entered failed phase",
                )
            }
            inspect(event.snapshot)
        }

        private fun inspect(snapshot: AsteriskdSnapshot) {
            if (snapshot.phase == AsteriskdPhase.Failed) {
                error(snapshot.error?.message ?: "asteriskd entered failed phase")
            }
            if (snapshot.phase == AsteriskdPhase.Running) {
                runningSnapshot = snapshot
                return
            }
            check(snapshot.phase != AsteriskdPhase.Stopped) {
                "asteriskd stopped before reaching running phase"
            }
        }
    }
}
