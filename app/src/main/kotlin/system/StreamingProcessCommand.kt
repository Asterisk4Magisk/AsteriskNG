// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package system

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import utils.shellQuote

internal class StreamingProcessCommand private constructor(
    val shellCommand: String,
    private val marker: String,
) {
    fun processIdOrNull(line: String): Long? {
        if (!line.startsWith(marker)) return null
        return line.substring(marker.length).toLongOrNull()?.takeIf { processId -> processId > 0L }
    }

    companion object {
        fun create(
            command: String,
            marker: String = "__asterisk_stream_pid_${UUID.randomUUID()}__:",
        ): StreamingProcessCommand {
            require(marker.isNotEmpty() && '\n' !in marker && '\r' !in marker)
            val payload = "printf '%s%s\\n' ${marker.shellQuote()} \"\$\$\"; exec $command"
            return StreamingProcessCommand(
                shellCommand = "sh -c ${payload.shellQuote()}",
                marker = marker,
            )
        }
    }
}

internal class StreamingProcessLifetime(
    private val terminateProcess: (Long) -> Unit,
) {
    private val state = AtomicLong(ProcessUnavailable)

    fun publishProcessId(processId: Long) {
        require(processId > 0L)
        if (!state.compareAndSet(ProcessUnavailable, processId) && state.get() == Cancelled) {
            terminateProcess(processId)
        }
    }

    fun cancel() {
        val processId = state.getAndSet(Cancelled)
        if (processId > 0L) terminateProcess(processId)
    }

    private companion object {
        const val Cancelled = -1L
        const val ProcessUnavailable = 0L
    }
}
