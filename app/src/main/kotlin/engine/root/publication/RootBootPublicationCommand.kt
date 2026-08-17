// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.publication

import utils.shellQuote

internal const val RootBootScriptDir = "/data/adb/service.d"
internal const val RootBootScriptPath = "$RootBootScriptDir/asteriskng_start.sh"

internal object RootBootPublicationCommand {
    fun buildRemoval(layout: RootRuntimeLayout): String = buildString {
        appendLine("set -eu")
        RootPublicationRequiredTools.forEach { tool ->
            appendLine("command -v $tool >/dev/null 2>&1 || exit 70")
        }
        appendStatusMustBeUnbound(layout)
        appendRemoveOwnedBoot(this, layout)
    }.trimEnd()

    internal fun appendRemoveOwnedBoot(builder: StringBuilder, layout: RootRuntimeLayout) = with(builder) {
        val expectedStartup = buildStartupScript(layout).trimEnd()
        val expectedService = buildServiceScript(layout).trimEnd()
        appendLine("remove_owned_boot() {")
        appendLine("  if [ -f ${layout.startupScriptPath.shellQuote()} ] && [ ! -L ${layout.startupScriptPath.shellQuote()} ] && " +
            "[ \"$(cat ${layout.startupScriptPath.shellQuote()})\" = ${expectedStartup.shellQuote()} ]; then")
        appendLine("    rm -f ${layout.startupScriptPath.shellQuote()}")
        appendLine("  fi")
        appendLine("  if [ -f ${RootBootScriptPath.shellQuote()} ] && [ ! -L ${RootBootScriptPath.shellQuote()} ] && " +
            "[ \"$(cat ${RootBootScriptPath.shellQuote()})\" = ${expectedService.shellQuote()} ]; then")
        appendLine("    rm -f ${RootBootScriptPath.shellQuote()}")
        appendLine("  fi")
        appendLine("}")
        appendLine("remove_owned_boot")
    }

    internal fun buildStartupScript(layout: RootRuntimeLayout): String = """
        #!/system/bin/sh
        set -eu
        exec ${layout.asteriskdPath.shellQuote()} start --config ${layout.asteriskdConfigPath.shellQuote()}
    """.trimIndent() + "\n"

    internal fun buildServiceScript(layout: RootRuntimeLayout): String = """
        #!/system/bin/sh
        while [ \"$(getprop sys.boot_completed)\" != \"1\" ]; do sleep 1; done
        exec /system/bin/sh ${layout.startupScriptPath.shellQuote()}
    """.trimIndent() + "\n"

    private fun StringBuilder.appendStatusMustBeUnbound(layout: RootRuntimeLayout) {
        appendLine("set +e")
        appendLine("asteriskd_status=\"$(${layout.asteriskdPath.shellQuote()} status)\"")
        appendLine("asteriskd_status_code=\"$?\"")
        appendLine("set -e")
        appendLine("if [ \"\$asteriskd_status_code\" -ne 3 ]; then")
        appendLine("  printf '%s\\n' \"\$asteriskd_status\"")
        appendLine("  exit \"\$asteriskd_status_code\"")
        appendLine("fi")
    }
}
