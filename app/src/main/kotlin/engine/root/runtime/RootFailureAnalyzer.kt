// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import engine.root.mode.RootModeCatalog

/** Report actual errors only; core-specific remedies require evidence from that core. */
internal object RootFailureAnalyzer {
    fun analyze(runMode: Int, error: Throwable, occurredAtEpochMillis: Long): ProxyErrorExplanation {
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        var message = error.javaClass.simpleName
        while (current != null && visited.add(current)) {
            current.message?.takeIf(String::isNotBlank)?.let { message = it }
            current = current.cause
        }
        return explanation(
            RootModeCatalog.find(runMode)?.daemonMode?.wireValue ?: "unknown",
            message,
            occurredAtEpochMillis,
        )
    }

    fun analyze(
        errorCode: String,
        exitCode: Int?,
        message: String?,
        mode: String,
        extraContext: String? = null,
        occurredAtEpochMillis: Long,
    ): ProxyErrorExplanation = explanation(
        mode.ifBlank { "unknown" },
        buildString {
            append("[$errorCode] ")
            append(message.orEmpty())
            if (exitCode != null) append(" (exitCode=$exitCode)")
            if (!extraContext.isNullOrBlank()) append("\n$extraContext")
        },
        occurredAtEpochMillis,
    )

    private fun explanation(mode: String, message: String, time: Long) = ProxyErrorExplanation(
        mode = mode,
        occurredAtEpochMillis = time,
        rawMessage = DiagnosticRedaction.redact(message),
        diagnostics = emptyList(),
    )
}
