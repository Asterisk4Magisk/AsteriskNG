// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.publication

import utils.shellQuote

internal object RootPublicationCommand {
    fun build(bundle: RootPublicationBundle): String {
        val layout = bundle.runtimeLayout
        val startup = RootBootPublicationCommand.buildStartupScript(layout)
        val service = RootBootPublicationCommand.buildServiceScript(layout)
        val expectedStartup = startup.trimEnd()
        val expectedService = service.trimEnd()
        val startupSize = startup.toByteArray(Charsets.UTF_8).size
        val serviceSize = service.toByteArray(Charsets.UTF_8).size
        return buildString {
            appendLine("set -eu")
            RootPublicationRequiredTools.forEach { tool ->
                appendLine("command -v $tool >/dev/null 2>&1 || exit 70")
            }
            appendLine("[ -x ${layout.asteriskdPath.shellQuote()} ] || exit 70")
            appendLine("[ -d ${layout.dataDir.shellQuote()} ] || exit 70")
            appendLine("[ -f ${bundle.coreConfigSourcePath.shellQuote()} ] && [ ! -L ${bundle.coreConfigSourcePath.shellQuote()} ] || exit 70")
            appendLine("[ -f ${bundle.asteriskdConfigSourcePath.shellQuote()} ] && [ ! -L ${bundle.asteriskdConfigSourcePath.shellQuote()} ] || exit 70")
            bundle.restartExpectedOwner?.let { owner ->
                appendConditionalStop(layout, owner)
            }
            appendStatusMustBeUnbound(layout)
            RootLegacyMigrationCommand.appendGate(this, layout)
            appendStatusMustBeUnbound(layout)
            appendStageFunctions(layout)
            appendLine("core_config_tmp=")
            appendLine("asteriskd_config_tmp=")
            appendLine("startup_tmp=")
            appendLine("service_tmp=")
            appendLine("trap 'rm -f \"\$core_config_tmp\" \"\$asteriskd_config_tmp\" \"\$startup_tmp\" \"\$service_tmp\"' EXIT HUP INT TERM")
            appendLine("core_config_tmp=\"$(prepare_source_file ${layout.configPath.shellQuote()} 600 ${bundle.coreConfigSourcePath.shellQuote()})\"")
            appendLine("asteriskd_config_tmp=\"$(prepare_source_file ${layout.asteriskdConfigPath.shellQuote()} 600 ${bundle.asteriskdConfigSourcePath.shellQuote()})\"")
            if (bundle.bootEnabled) {
                appendLine("mkdir -p ${RootBootScriptDir.shellQuote()}")
                appendLine("if ! content_file_is_current ${layout.startupScriptPath.shellQuote()} 700 ${expectedStartup.shellQuote()} $startupSize; then")
                appendLine("  startup_tmp=\"$(prepare_content_file ${layout.startupScriptPath.shellQuote()} 700 ${startup.shellQuote()})\"")
                appendLine("fi")
                appendLine("if ! content_file_is_current ${RootBootScriptPath.shellQuote()} 700 ${expectedService.shellQuote()} $serviceSize; then")
                appendLine("  service_tmp=\"$(prepare_content_file ${RootBootScriptPath.shellQuote()} 700 ${service.shellQuote()})\"")
                appendLine("fi")
            }
            appendLine("publish_file \"\$core_config_tmp\" ${layout.configPath.shellQuote()}")
            appendLine("core_config_tmp=")
            appendLine("publish_file \"\$asteriskd_config_tmp\" ${layout.asteriskdConfigPath.shellQuote()}")
            appendLine("asteriskd_config_tmp=")
            if (bundle.bootEnabled) {
                appendLine("[ -z \"\$startup_tmp\" ] || publish_file \"\$startup_tmp\" ${layout.startupScriptPath.shellQuote()}")
                appendLine("startup_tmp=")
                appendLine("[ -z \"\$service_tmp\" ] || publish_file \"\$service_tmp\" ${RootBootScriptPath.shellQuote()}")
                appendLine("service_tmp=")
            } else {
                RootBootPublicationCommand.appendRemoveOwnedBoot(this, layout)
            }
            appendLine("trap - EXIT HUP INT TERM")
            val launchCommand = when (bundle.launchMode) {
                RootPublicationLaunchMode.None -> null
                RootPublicationLaunchMode.Service -> "start"
                RootPublicationLaunchMode.Monitor -> "monitor"
            }
            if (launchCommand != null) {
                appendLine(
                    "nohup setsid ${layout.asteriskdPath.shellQuote()} $launchCommand " +
                        "--config ${layout.asteriskdConfigPath.shellQuote()} " +
                        "</dev/null >/dev/null 2>>${layout.asteriskdLogPath.shellQuote()} &",
                )
            }
        }.trimEnd()
    }

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

    private fun StringBuilder.appendStageFunctions(layout: RootRuntimeLayout) {
        appendLine("prepare_metadata() {")
        appendLine("  temporary=\"\$1\"")
        appendLine("  parent=\"\$2\"")
        appendLine("  target_mode=\"\$3\"")
        appendLine("  target_uid=\"$(stat -c %u \"\$parent\")\" || return 1")
        appendLine("  target_gid=\"$(stat -c %g \"\$parent\")\" || return 1")
        appendLine("  chown \"\$target_uid:\$target_gid\" \"\$temporary\" || return 1")
        appendLine("  chmod \"\$target_mode\" \"\$temporary\" || return 1")
        appendLine("  restorecon_output=\"$(restorecon \"\$temporary\" 2>&1)\" || { printf '%s\\n' \"\$restorecon_output\" >&2; return 1; }")
        appendLine("  [ \"$(stat -c %u \"\$temporary\")\" = \"\$target_uid\" ] || return 1")
        appendLine("  [ \"$(stat -c %g \"\$temporary\")\" = \"\$target_gid\" ] || return 1")
        appendLine("  [ \"$(stat -c %a \"\$temporary\")\" = \"\$target_mode\" ] || return 1")
        appendLine("  ${layout.asteriskdPath.shellQuote()} sync --file \"\$temporary\" || return 1")
        appendLine("}")
        appendLine("prepare_source_file() {")
        appendLine("  target=\"\$1\"")
        appendLine("  target_mode=\"\$2\"")
        appendLine("  source=\"\$3\"")
        appendLine("  parent=\"${'$'}{target%/*}\"")
        appendLine("  temporary=\"$(mktemp \"\$parent/.asteriskd.XXXXXX\")\"")
        appendLine("  cp -- \"\$source\" \"\$temporary\" || { rm -f \"\$temporary\"; return 1; }")
        appendLine("  prepare_metadata \"\$temporary\" \"\$parent\" \"\$target_mode\" || { rm -f \"\$temporary\"; return 1; }")
        appendLine("  printf '%s\\n' \"\$temporary\"")
        appendLine("}")
        appendLine("content_file_is_current() {")
        appendLine("  target=\"\$1\"")
        appendLine("  target_mode=\"\$2\"")
        appendLine("  expected_content=\"\$3\"")
        appendLine("  expected_size=\"\$4\"")
        appendLine("  parent=\"${'$'}{target%/*}\"")
        appendLine("  [ -f \"\$target\" ] && [ ! -L \"\$target\" ] || return 1")
        appendLine("  target_uid=\"$(stat -c %u \"\$target\")\" || return 1")
        appendLine("  target_gid=\"$(stat -c %g \"\$target\")\" || return 1")
        appendLine("  [ \"\$target_uid\" = \"$(stat -c %u \"\$parent\")\" ] || return 1")
        appendLine("  [ \"\$target_gid\" = \"$(stat -c %g \"\$parent\")\" ] || return 1")
        appendLine("  [ \"$(stat -c %a \"\$target\")\" = \"\$target_mode\" ] || return 1")
        appendLine("  [ \"$(wc -c < \"\$target\")\" = \"\$expected_size\" ] || return 1")
        appendLine("  [ \"$(cat \"\$target\")\" = \"\$expected_content\" ] || return 1")
        appendLine("}")
        appendLine("prepare_content_file() {")
        appendLine("  target=\"\$1\"")
        appendLine("  target_mode=\"\$2\"")
        appendLine("  content=\"\$3\"")
        appendLine("  parent=\"${'$'}{target%/*}\"")
        appendLine("  temporary=\"$(mktemp \"\$parent/.asteriskd.XXXXXX\")\"")
        appendLine("  printf '%s' \"\$content\" > \"\$temporary\" || { rm -f \"\$temporary\"; return 1; }")
        appendLine("  prepare_metadata \"\$temporary\" \"\$parent\" \"\$target_mode\" || { rm -f \"\$temporary\"; return 1; }")
        appendLine("  printf '%s\\n' \"\$temporary\"")
        appendLine("}")
        appendLine("publish_file() {")
        appendLine("  temporary=\"\$1\"")
        appendLine("  target=\"\$2\"")
        appendLine("  parent=\"${'$'}{target%/*}\"")
        appendLine("  mv -f \"\$temporary\" \"\$target\"")
        appendLine("  ${layout.asteriskdPath.shellQuote()} sync --directory \"\$parent\"")
        appendLine("}")
    }

}

private const val SocketReleasePollAttempts = 50
internal val RootPublicationRequiredTools = listOf(
    "mktemp",
    "grep",
    "stat",
    "wc",
    "tail",
    "awk",
    "tr",
    "cat",
    "cp",
    "mkdir",
    "chown",
    "chmod",
    "restorecon",
    "mv",
    "rm",
    "sleep",
    "nohup",
    "setsid",
)
