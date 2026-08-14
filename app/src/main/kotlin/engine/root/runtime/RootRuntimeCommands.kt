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
        appendLine("asteriskd_attempt=0")
        appendLine("while [ \"\$asteriskd_attempt\" -lt $SocketReleasePollAttempts ]; do")
        appendLine("  set +e")
        appendLine("  ${layout.asteriskdPath.shellQuote()} status >/dev/null")
        appendLine("  asteriskd_status_code=\"\$?\"")
        appendLine("  set -e")
        appendLine("  [ \"\$asteriskd_status_code\" -eq 3 ] && { printf '%s\\n' \"\$asteriskd_stop\"; exit 0; }")
        appendLine("  asteriskd_attempt=\$((asteriskd_attempt + 1))")
        appendLine("  sleep 0.1")
        appendLine("done")
        appendLine("printf '%s\\n' \"\$asteriskd_stop\"")
        appendLine("exit 1")
        appendLine(")")
    }.trimEnd()
}

private const val SocketReleasePollAttempts = 50
