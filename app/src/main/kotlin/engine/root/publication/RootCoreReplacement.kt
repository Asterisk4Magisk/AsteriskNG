// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.publication

import utils.shellQuote

internal object RootCoreReplacementCommand {
    fun build(
        layout: RootRuntimeLayout,
        candidatePath: String,
        sha256: String,
    ): String {
        require(candidatePath.startsWith('/') && '\n' !in candidatePath && '\r' !in candidatePath)
        require(sha256.length == Sha256HexLength && sha256.all { character ->
            character in '0'..'9' || character in 'a'..'f'
        })
        return buildString {
            appendLine("set -eu")
            RequiredTools.forEach { tool -> appendLine("command -v $tool >/dev/null 2>&1 || exit 70") }
            appendLine("[ -x ${layout.asteriskdPath.shellQuote()} ] || exit 70")
            appendLine("[ -d ${layout.dataDir.shellQuote()} ] && [ ! -L ${layout.dataDir.shellQuote()} ] || exit 70")
            appendLine("[ -f ${candidatePath.shellQuote()} ] && [ ! -L ${candidatePath.shellQuote()} ] || exit 70")
            appendStatusMustBeUnbound(layout)
            appendLine("core_tmp=")
            appendLine("trap 'rm -f \"\$core_tmp\"' EXIT HUP INT TERM")
            appendLine("core_tmp=\"$(mktemp \"${layout.dataDir}/.xray.XXXXXX\")\"")
            appendLine("cp -- ${candidatePath.shellQuote()} \"\$core_tmp\"")
            appendLine("core_uid=\"$(stat -c %u ${layout.dataDir.shellQuote()})\"")
            appendLine("core_gid=\"$(stat -c %g ${layout.dataDir.shellQuote()})\"")
            appendLine("chown \"\$core_uid:\$core_gid\" \"\$core_tmp\"")
            appendLine("chmod 755 \"\$core_tmp\"")
            appendLine("restorecon \"\$core_tmp\" >/dev/null")
            appendLine("[ \"$(stat -c %u \"\$core_tmp\")\" = \"\$core_uid\" ]")
            appendLine("[ \"$(stat -c %g \"\$core_tmp\")\" = \"\$core_gid\" ]")
            appendLine("[ \"$(stat -c %a \"\$core_tmp\")\" = 755 ]")
            appendLine("core_digest=\"$(sha256sum \"\$core_tmp\")\"")
            appendLine("[ \"\${core_digest%% *}\" = ${sha256.shellQuote()} ] || exit 74")
            appendLine("${layout.asteriskdPath.shellQuote()} sync --file \"\$core_tmp\"")
            appendLine("mv -f \"\$core_tmp\" ${layout.xrayCorePath.shellQuote()}")
            appendLine("core_tmp=")
            appendLine("${layout.asteriskdPath.shellQuote()} sync --directory ${layout.dataDir.shellQuote()}")
            appendLine("trap - EXIT HUP INT TERM")
        }.trimEnd()
    }

    private fun StringBuilder.appendStatusMustBeUnbound(layout: RootRuntimeLayout) {
        appendLine("set +e")
        appendLine("asteriskd_status=\"$(${layout.asteriskdPath.shellQuote()} status)\"")
        appendLine("asteriskd_status_code=\"\$?\"")
        appendLine("set -e")
        appendLine("[ \"\$asteriskd_status_code\" -eq 3 ] || { printf '%s\\n' \"\$asteriskd_status\"; exit \"\$asteriskd_status_code\"; }")
    }
}

private const val Sha256HexLength = 64
private val RequiredTools = listOf(
    "mktemp",
    "cp",
    "stat",
    "chown",
    "chmod",
    "restorecon",
    "sha256sum",
    "mv",
    "rm",
)
