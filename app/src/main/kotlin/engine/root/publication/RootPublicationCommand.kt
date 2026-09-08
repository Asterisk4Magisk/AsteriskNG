// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.publication

import utils.shellQuote

internal object RootPublicationCommand {
    fun buildPreparation(bundle: RootPublicationBundle): String {
        val layout = bundle.runtimeLayout
        return buildString {
            appendLine("set -eu")
            RootPublicationRequiredTools.forEach { tool ->
                appendLine("command -v $tool >/dev/null 2>&1 || { printf '%s\\n' 'root_start missing_tool=$tool' >&2; exit 70; }")
            }
            appendLine("[ -x ${layout.asteriskdPath.shellQuote()} ] || exit 70")
            appendLine("[ -d ${layout.dataDir.shellQuote()} ] || exit 70")
            bundle.restartExpectedOwner?.let { owner ->
                appendConditionalStop(layout, owner)
            }
            appendStatusMustBePublishable(layout)
            RootLegacyMigrationCommand.appendGate(this, layout)
            appendStatusMustBePublishable(layout)
            appendServiceLogCleanup(layout)
        }.trimEnd()
    }

    fun buildLaunch(bundle: RootPublicationBundle): String {
        val layout = bundle.runtimeLayout
        return buildString {
            appendLine("set -eu")
            appendStatusMustBePublishable(layout)
            listOf(layout.configPath, layout.asteriskdConfigPath).forEach { path ->
                appendLine("[ -f ${path.shellQuote()} ] && [ ! -L ${path.shellQuote()} ] || exit 70")
            }
            if (bundle.bootEnabled) {
                RootBootPublicationCommand.appendInstallBoot(this, layout)
            } else {
                RootBootPublicationCommand.appendRemoveBoot(this, layout)
            }
            val launchCommand = when (bundle.launchMode) {
                RootPublicationLaunchMode.None -> null
                RootPublicationLaunchMode.Service -> "start"
                RootPublicationLaunchMode.Monitor -> "monitor"
            }
            if (launchCommand != null) {
                appendLine(
                    "nohup ${layout.asteriskdPath.shellQuote()} $launchCommand " +
                        "--config ${layout.asteriskdConfigPath.shellQuote()} " +
                        "</dev/null >/dev/null 2>>${layout.asteriskdLogPath.shellQuote()} &",
                )
            }
        }.trimEnd()
    }

    private fun StringBuilder.appendStatusMustBePublishable(layout: RootRuntimeLayout) {
        appendLine("set +e")
        appendLine("asteriskd_status=\"$(${layout.asteriskdPath.shellQuote()} status)\"")
        appendLine("asteriskd_status_code=\"$?\"")
        appendLine("set -e")
        appendLine("if [ \"\$asteriskd_status_code\" -ne 3 ]; then")
        appendLine("  printf '%s\\n' \"\$asteriskd_status\"")
        appendLine("  exit \"\$asteriskd_status_code\"")
        appendLine("fi")
    }

    private fun StringBuilder.appendServiceLogCleanup(layout: RootRuntimeLayout) {
        appendLine(
            "for service_log in ${layout.logDirectoryPath.shellQuote()}/* " +
                "${layout.logDirectoryPath.shellQuote()}/.[!.]* ${layout.logDirectoryPath.shellQuote()}/..?*; do",
        )
        appendLine("  [ -e \"\$service_log\" ] || continue")
        appendLine("  [ \"\${service_log##*/}\" = 'logcat.log' ] && continue")
        appendLine("  [ -f \"\$service_log\" ] && [ ! -L \"\$service_log\" ] || continue")
        appendLine("  rm -f -- \"\$service_log\" >/dev/null 2>&1 || { printf '%s\\n' \"$RootServiceLogCleanupWarningPrefix\$service_log\" >&2 || :; }")
        appendLine("done")
    }

    private fun StringBuilder.appendConditionalStop(
        layout: RootRuntimeLayout,
        expectedOwner: String,
    ) {
        appendLine("set +e")
        appendLine("asteriskd_status=\"$(${layout.asteriskdPath.shellQuote()} status)\"")
        appendLine("asteriskd_status_code=\"\$?\"")
        appendLine("set -e")
        appendLine("if [ \"\$asteriskd_status_code\" -ne 3 ]; then")
        appendLine("  case \"\$asteriskd_status\" in")
        appendLine("    *'\"owner\":\"$expectedOwner\"'*) ;;")
        appendLine("    *) printf '%s\\n' \"\$asteriskd_status\"; exit \"\$asteriskd_status_code\" ;;")
        appendLine("  esac")
        appendLine("  set +e")
        appendLine("  asteriskd_shutdown=\"$(${layout.asteriskdPath.shellQuote()} shutdown)\"")
        appendLine("  asteriskd_shutdown_code=\"\$?\"")
        appendLine("  set -e")
        appendLine("  [ \"\$asteriskd_shutdown_code\" -eq 0 ] || [ \"\$asteriskd_shutdown_code\" -eq 3 ] || { printf '%s\\n' \"\$asteriskd_shutdown\"; exit \"\$asteriskd_shutdown_code\"; }")
        appendLine("  asteriskd_attempt=0")
        appendLine("  while [ \"\$asteriskd_attempt\" -lt $SocketReleasePollAttempts ]; do")
        appendLine("    set +e")
        appendLine("    ${layout.asteriskdPath.shellQuote()} status >/dev/null")
        appendLine("    asteriskd_status_code=\"\$?\"")
        appendLine("    set -e")
        appendLine("    [ \"\$asteriskd_status_code\" -eq 3 ] && break")
        appendLine("    asteriskd_attempt=\$((asteriskd_attempt + 1))")
        appendLine("    sleep 0.1")
        appendLine("  done")
        appendLine("  [ \"\$asteriskd_status_code\" -eq 3 ] || exit 75")
        appendLine("fi")
    }

}

private const val SocketReleasePollAttempts = 50
internal const val RootServiceLogCleanupWarningPrefix = "Failed to clear service log: "
internal val RootPublicationRequiredTools = listOf(
    "grep",
    "stat",
    "tr",
    "mkdir",
    "chmod",
    "rm",
    "sleep",
    "nohup",
)
