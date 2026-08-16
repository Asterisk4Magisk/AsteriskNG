// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import engine.root.publication.RootRuntimeLayout
import utils.shellQuote

internal object RootStopOwnCommand {
    fun build(layout: RootRuntimeLayout): String = buildString {
        appendLine("(")
        appendLine("set -eu")
        appendLine("set +e")
        appendLine("asteriskd_status=\"$(${layout.asteriskdPath.shellQuote()} status)\"")
        appendLine("asteriskd_status_code=\"\$?\"")
        appendLine("set -e")
        appendLine("case \"\$asteriskd_status\" in")
        appendLine("  *'\"owner\":\"asteriskng\"'*) ;;")
        appendLine("  *) printf '%s\\n' \"\$asteriskd_status\"; exit \"\$asteriskd_status_code\" ;;")
        appendLine("esac")
        appendLine("set +e")
        appendLine("asteriskd_stop=\"$(${layout.asteriskdPath.shellQuote()} stop)\"")
        appendLine("asteriskd_stop_code=\"\$?\"")
        appendLine("set -e")
        appendLine("[ \"\$asteriskd_stop_code\" -eq 0 ] || { printf '%s\\n' \"\$asteriskd_stop\"; exit \"\$asteriskd_stop_code\"; }")
        appendLine("printf '%s\\n' \"\$asteriskd_stop\"")
        appendLine(")")
    }.trimEnd()
}

internal object RootShutdownOwnCommand {
    fun build(layout: RootRuntimeLayout): String {
        val base = RootStopOwnCommand.build(layout)
            .replace("asteriskd_stop", "asteriskd_shutdown")
            .replace(" stop)", " shutdown)")
        val failureCheck =
            "[ \"\$asteriskd_shutdown_code\" -eq 0 ] || { printf '%s\\n' \"\$asteriskd_shutdown\"; " +
                "exit \"\$asteriskd_shutdown_code\"; }"
        val shutdown = base.replace(
            failureCheck,
            buildString {
                appendLine("case \"\$asteriskd_shutdown\" in")
                appendLine("  *'\"code\":\"invalid_request\"'*)")
                appendLine("    set +e")
                appendLine("    asteriskd_shutdown=\"\$(${layout.asteriskdPath.shellQuote()} stop)\"")
                appendLine("    asteriskd_shutdown_code=\"\$?\"")
                appendLine("    set -e")
                appendLine("    ;;")
                appendLine("esac")
                append(failureCheck)
            },
        )
        check(shutdown != base) { "Shutdown command failure check was not found" }
        val responsePrint = "printf '%s\\n' \"\$asteriskd_shutdown\""
        val shutdownWithoutEagerResponse = shutdown.substringBeforeLast(responsePrint)
        check(shutdownWithoutEagerResponse != shutdown) { "Shutdown response print was not found" }
        return shutdownWithoutEagerResponse + buildString {
            appendLine("asteriskd_attempt=0")
            appendLine("while [ \"\$asteriskd_attempt\" -lt $SocketReleasePollAttempts ]; do")
            appendLine("  set +e")
            appendLine("  ${layout.asteriskdPath.shellQuote()} status >/dev/null")
            appendLine("  asteriskd_status_code=\"\$?\"")
            appendLine("  set -e")
            appendLine("  [ \"\$asteriskd_status_code\" -eq 3 ] && { printf '%s\\n' \"\$asteriskd_shutdown\"; exit 0; }")
            appendLine("  asteriskd_attempt=\$((asteriskd_attempt + 1))")
            appendLine("  sleep 0.1")
            appendLine("done")
            appendLine("printf '%s\\n' \"\$asteriskd_shutdown\"")
            appendLine("exit 1")
            append(")")
        }
    }
}

private const val SocketReleasePollAttempts = 50
