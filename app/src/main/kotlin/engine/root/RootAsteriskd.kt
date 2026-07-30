// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root

import android.content.Context
import engine.hevtun.HevSocks5TunnelConfigFileName
import engine.hevtun.HevSocks5TunnelPidFileName
import features.resources.runtime.writeAtomically
import kotlinx.serialization.json.Json
import utils.shellQuote
import utils.shellQuoteForCase
import java.io.File

internal val RootStartConfig.disableSystemIpv6: Boolean
    get() = !enableIpv6 && enableRootIpv6Disabler

internal fun RootStartConfig.buildAsteriskdConfig(
    mode: AsteriskdMode,
    iptablesConfig: RootIptablesConfig,
    virtualInterfaces: List<String>,
    bypassConsumerChains: AsteriskdBypassConsumerChains? = null,
): AsteriskdConfig {
    return AsteriskdConfig.forMode(
        mode = mode,
        enableIpv6 = enableIpv6,
        disableSystemIpv6 = disableSystemIpv6,
        readyPath = runtimeLayout.asteriskdReadyPath,
        ignoredInterfaces = iptablesConfig.ignoredInterfaces,
        virtualInterfaces = virtualInterfaces,
        hotspotInterfacePrefixes = iptablesConfig.externalInterfacePrefixes,
        bypassConsumerChains = bypassConsumerChains,
        stopScriptPath = runtimeLayout.stopScriptPath,
        statePath = runtimeLayout.asteriskdStatePath,
        emergencyProcesses = runtimeLayout.asteriskdEmergencyProcesses(),
    )
}

private fun RootRuntimeLayout.asteriskdEmergencyProcesses(): List<AsteriskdEmergencyProcess> {
    return listOf(
        AsteriskdEmergencyProcess(
            pidPath = pidPath,
            commandMarker = configPath,
        ),
        AsteriskdEmergencyProcess(
            pidPath = bpf2socksPidPath,
            commandMarker = bpf2socksConfigPath,
        ),
        AsteriskdEmergencyProcess(
            pidPath = File(dataDir, HevSocks5TunnelPidFileName).absolutePath,
            commandMarker = File(dataDir, HevSocks5TunnelConfigFileName).absolutePath,
        ),
    )
}

internal fun writeAsteriskdConfig(
    config: AsteriskdConfig,
    configPath: String,
) {
    writeAtomically(File(configPath)) { output ->
        output.write(AsteriskdJson.encodeToString(AsteriskdConfig.serializer(), config).toByteArray(Charsets.UTF_8))
    }
}

internal fun RootStartConfig.buildPrepareAsteriskdCommand(): String {
    return buildString {
        // An APK update changes the native library path. Remove a monitor started
        // from the previous package before it can maintain the new runtime state.
        append(runtimeLayout.buildStopAsteriskdCommand())
        appendScript(
            """
            mkdir -p ${runtimeLayout.dataDir.shellQuote()} || exit 1
            rm -f ${runtimeLayout.asteriskdPidPath.shellQuote()} ${runtimeLayout.asteriskdReadyPath.shellQuote()} 2>/dev/null || true
            chmod 755 ${runtimeLayout.asteriskdPath.shellQuote()} || exit 1
            ${runtimeLayout.asteriskdPath.shellQuote()} --prepare --config ${runtimeLayout.asteriskdConfigPath.shellQuote()} || exit 1
            """,
        )
    }
}

internal fun RootStartConfig.buildStartAsteriskdCommand(): String {
    val logPath = runtimeLayout.asteriskdLogPath
    val legacyLogPath = File(runtimeLayout.dataDir, RootAsteriskdLogFileName).absolutePath
    val logDirectoryPath = File(logPath).parentFile?.absolutePath
    return buildString {
        append(runtimeLayout.buildStopAsteriskdCommand())
        logDirectoryPath?.let { path ->
            appendScript("mkdir -p ${path.shellQuote()} || exit 1")
        }
        appendScript(
            $$"""
            rm -f $${legacyLogPath.shellQuote()} 2>/dev/null || true
            rm -f $${logPath.shellQuote()} 2>/dev/null || true
            touch $${logPath.shellQuote()} || exit 1
            chmod 666 $${logPath.shellQuote()} || exit 1
            rm -f $${runtimeLayout.asteriskdReadyPath.shellQuote()} 2>/dev/null || true
            $${runtimeLayout.asteriskdPath.shellQuote()} --start --config $${runtimeLayout.asteriskdConfigPath.shellQuote()} --pid $${runtimeLayout.asteriskdPidPath.shellQuote()} --log $${logPath.shellQuote()} >> $${logPath.shellQuote()} 2>&1 < /dev/null &
            asteriskd_pid="$!"
            echo "$asteriskd_pid" > $${runtimeLayout.asteriskdPidPath.shellQuote()}
            """,
        )
    }
}

internal fun RootRuntimeLayout.buildStopAsteriskdCommand(): String {
    val processMatchTest = buildAsteriskdProcessMatchTest().trimEnd()
    val configPath = asteriskdConfigPath.shellQuoteForCase()
    return buildString {
        appendScript(
            $$"""
            terminate_asteriskd_process() {
                asteriskd_target_pid="$1"
                [ -n "$asteriskd_target_pid" ] || return 0
                kill "$asteriskd_target_pid" 2>/dev/null || true
                asteriskd_attempt=0
                while [ "$asteriskd_attempt" -lt 20 ] && kill -0 "$asteriskd_target_pid" 2>/dev/null; do
                    asteriskd_attempt=$((asteriskd_attempt + 1))
                    sleep 0.1
                done
                kill -9 "$asteriskd_target_pid" 2>/dev/null || true
            }

            # First stop the PID recorded by the current runtime, if it still
            # points at the expected executable and root identity.
            pid="$(cat $${asteriskdPidPath.shellQuote()} 2>/dev/null || true)"
            if [ -n "$pid" ] && $${processMatchTest}; then
                terminate_asteriskd_process "$pid"
            fi

            # Package replacement leaves native processes running from a deleted
            # library path. Their PID file may already be gone, so scan /proc and
            # only match asteriskd processes tied to this runtime's config path.
            if command -v pidof >/dev/null 2>&1; then
                asteriskd_pid_candidates="$(pidof libasteriskd.so asteriskd 2>/dev/null || true)"
            else
                asteriskd_pid_candidates=""
                for asteriskd_pid_dir in /proc/[0-9]*; do
                    asteriskd_pid_candidates="$asteriskd_pid_candidates ${asteriskd_pid_dir##*/}"
                done
            fi
            for asteriskd_pid in $asteriskd_pid_candidates; do
                asteriskd_pid_dir="/proc/$asteriskd_pid"
                [ -d "$asteriskd_pid_dir" ] || continue
                cmdline="$(tr '\0' ' ' < "$asteriskd_pid_dir/cmdline" 2>/dev/null || true)"
                argv0="${cmdline%% *}"
                argv0_base="${argv0##*/}"
                case "$argv0_base" in
                    asteriskd|libasteriskd.so) ;;
                    *) continue ;;
                esac
                case "$cmdline" in *$$configPath*) ;;
                    *) continue ;;
                esac
                uid_line="$(grep '^Uid:' "$asteriskd_pid_dir/status" 2>/dev/null || true)"
                set -- $uid_line
                [ "$3" = "$${RootAsteriskdUid}" ] || [ "$5" = "$${RootAsteriskdUid}" ] || continue
                gid_line="$(grep '^Gid:' "$asteriskd_pid_dir/status" 2>/dev/null || true)"
                set -- $gid_line
                [ "$3" = "$${RootAsteriskdGid}" ] || [ "$5" = "$${RootAsteriskdGid}" ] || continue
                terminate_asteriskd_process "$asteriskd_pid"
            done
            rm -f $${asteriskdPidPath.shellQuote()} $${asteriskdReadyPath.shellQuote()} 2>/dev/null || true
            """,
        )
    }
}

internal fun RootRuntimeLayout.buildAsteriskdReadyCommand(): String {
    return buildString {
        appendScript("[ -f ${asteriskdReadyPath.shellQuote()} ] || exit 1")
        append(
            buildRootProcessMatchCommand(
                pidPath = asteriskdPidPath,
                executablePath = asteriskdPath,
                uid = RootAsteriskdUid,
                gid = RootAsteriskdGid,
            ),
        )
    }
}

internal fun Context.deleteAsteriskdLogFile() {
    val file = applicationContext.prepareRootRuntimeLayout().asteriskdLogPath.let(::File)
    if (file.exists() && !file.delete()) {
        error("Failed to delete ${file.absolutePath}")
    }
}

private fun RootRuntimeLayout.buildAsteriskdProcessMatchTest(): String {
    return buildRootProcessMatchTest(
        executablePath = asteriskdPath,
        uid = RootAsteriskdUid,
        gid = RootAsteriskdGid,
    )
}

private val AsteriskdJson = Json {
    encodeDefaults = true
    prettyPrint = true
    prettyPrintIndent = "  "
}
