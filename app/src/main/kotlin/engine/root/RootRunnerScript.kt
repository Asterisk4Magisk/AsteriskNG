// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root

import engine.xray.XrayCoreLogPaths
import engine.xray.logFilePaths
import engine.xray.logDirectoryPath
import features.resources.runtime.writeAtomically
import system.AndroidRootShellGateway
import system.ShellExecOptions
import utils.shellQuote
import utils.shellQuoteForCase
import utils.toTrimmedNonEmptyList
import java.io.File

internal fun writeRootConfigFile(config: RootStartConfig) {
    writeAtomically(File(config.configPath)) { output ->
        output.write(config.xrayConfigJson.toByteArray(Charsets.UTF_8))
    }
}

internal fun buildRemoveRootBootScriptCommand(
    runtimeLayout: RootRuntimeLayout,
    coreLogPaths: XrayCoreLogPaths,
): String {
    val bootLogPath = File(coreLogPaths.logDirectoryPath(), RootBootLogFileName).absolutePath
    return buildString {
        appendScript("rm -f ${RootBootScriptPath.shellQuote()} 2>/dev/null || true")
        appendScript("rm -f ${runtimeLayout.startupScriptPath.shellQuote()} 2>/dev/null || true")
        appendScript("rm -f ${bootLogPath.shellQuote()} 2>/dev/null || true")
    }
}

internal suspend fun AndroidRootShellGateway.removeRootBootScript(
    runtimeLayout: RootRuntimeLayout,
    coreLogPaths: XrayCoreLogPaths,
    failureMessage: String,
) {
    val result = exec(
        buildRemoveRootBootScriptCommand(runtimeLayout, coreLogPaths),
        ShellExecOptions(logFailure = false),
    )
    if (result.errno != 0) {
        error(
            buildRootDiagnosticMessage(
                failureMessage,
                result.stderr,
                result.stdout,
            ),
        )
    }
}

internal fun buildRootStopCommand(
    runtimeLayout: RootRuntimeLayout?,
    uid: Int,
    gid: Int,
    cleanupRulesCommand: String,
): String {
    return buildString {
        runtimeLayout?.let { paths ->
            append(paths.buildStopIpv6DisablerCommand())
            appendScript(
                $$"""
                pid="$(cat $${paths.pidPath.shellQuote()} 2>/dev/null || true)"
                if [ -n "$pid" ] && $${buildRootProcessMatchTest(paths.xrayCorePath, uid, gid).trimEnd()}; then
                    kill "$pid" 2>/dev/null || true
                    sleep 0.2
                    kill -9 "$pid" 2>/dev/null || true
                fi
                rm -f $${paths.pidPath.shellQuote()} 2>/dev/null || true
                """,
            )
        }
        append(cleanupRulesCommand)
        runtimeLayout?.let { paths ->
            append(paths.buildStopRootEbpfCommand())
        }
    }
}

internal fun RootStartConfig.buildPrepareRuntimeCommand(): String {
    return buildString {
        appendScript(
            """
            rm -f ${runtimeLayout.pidPath.shellQuote()} 2>/dev/null || true
            chmod 755 ${runtimeLayout.xrayCorePath.shellQuote()}
            """,
        )
    }
}

internal fun RootStartConfig.buildStartDaemonCommand(): String {
    return buildString {
        appendScript(
            $$"""
            trap '' HUP
            cd $${runtimeLayout.dataDir.shellQuote()} || exit 1
            export XRAY_LOCATION_ASSET=$${runtimeLayout.dataDir.shellQuote()}
            ulimit -SHn 1000000 2>/dev/null || true
            chmod 755 $${setuidgidPath.shellQuote()}
            $${setuidgidPath.shellQuote()} $${RootXrayUid.toString().shellQuote()} $${RootXrayGid.toString().shellQuote()} $${runtimeLayout.xrayCorePath.shellQuote()} run -config $${configPath.shellQuote()} >> $${coreLogPaths.errorLogPath.shellQuote()} 2>&1 < /dev/null &
            echo $! > $${runtimeLayout.pidPath.shellQuote()}
            """,
        )
    }
}

internal fun RootStartConfig.buildBootStartDaemonCommand(): String {
    return buildString {
        appendScript(
            $$"""
            trap '' HUP
            cd $${runtimeLayout.dataDir.shellQuote()} || exit 1
            export XRAY_LOCATION_ASSET=$${runtimeLayout.dataDir.shellQuote()}
            ulimit -SHn 1000000 || true
            chmod 755 $${setuidgidPath.shellQuote()}
            echo "+ start Xray-core as uid=$${RootXrayUid} gid=$${RootXrayGid}"
            echo "+ Xray stdout/stderr: $${coreLogPaths.errorLogPath.shellQuote()}"
            $${setuidgidPath.shellQuote()} $${RootXrayUid.toString().shellQuote()} $${RootXrayGid.toString().shellQuote()} $${runtimeLayout.xrayCorePath.shellQuote()} run -config $${configPath.shellQuote()} >> $${coreLogPaths.errorLogPath.shellQuote()} 2>&1 < /dev/null &
            echo $! > $${runtimeLayout.pidPath.shellQuote()}
            echo "Xray-core pid: $(cat $${runtimeLayout.pidPath.shellQuote()})"
            """,
        )
    }
}

internal fun XrayCoreLogPaths.buildPrepareCoreLogFilesCommand(): String {
    val logPaths = logFilePaths().filter(String::isNotBlank)
    return buildString {
        logPaths
            .mapNotNull { logPath -> File(logPath).parent }
            .distinct()
            .forEach { parentPath ->
                appendScript("mkdir -p ${parentPath.shellQuote()} || exit 1")
            }
        logPaths.forEach { logPath ->
            appendScript(
                """
                touch ${logPath.shellQuote()} || exit 1
                chmod 666 ${logPath.shellQuote()} || exit 1
                """,
            )
        }
    }
}

internal fun buildRootPortReadyCommand(port: Int): String {
    return "netstat -an 2>/dev/null | grep \"[.:]$port[[:space:]]\" | grep -E 'LISTEN|UNKNOWN' >/dev/null 2>&1"
}

internal fun Int.toNetstatPortHexMarker(): String {
    return ":${toString(16).uppercase().padStart(4, '0')} "
}

internal fun buildRootDiagnosticMessage(vararg sections: String): String {
    return sections.toList()
        .toTrimmedNonEmptyList()
        .joinToString("\n")
}

internal suspend fun RootStartConfig.collectRootErrorLogTail(rootAccess: AndroidRootShellGateway): String {
    val command = "tail -n 80 ${coreLogPaths.errorLogPath.shellQuote()} 2>/dev/null || true"
    val result = rootAccess.exec(command)
    return result.stdout.ifBlank { result.stderr }
}

internal suspend fun RootStartConfig.collectRootProcessDiagnostics(rootAccess: AndroidRootShellGateway): String {
    val command = $$"""
        pid="$(cat $${runtimeLayout.pidPath.shellQuote()} 2>/dev/null || true)"
        echo "pid=$pid"
        if [ -n "$pid" ]; then
            echo "cmdline=$(tr '\0' ' ' < /proc/"$pid"/cmdline 2>/dev/null || true)"
            echo "exe=$(readlink /proc/"$pid"/exe 2>/dev/null || true)"
            grep -E '^(Uid|Gid):' /proc/"$pid"/status 2>/dev/null || true
        fi
    """.trimIndent()
    val result = rootAccess.exec(command, ShellExecOptions(logFailure = false))
    return result.stdout.ifBlank { result.stderr }
}

internal fun buildRootProcessMatchCommand(
    pidPath: String,
    executablePath: String,
    uid: Int,
    gid: Int,
): String {
    return buildString {
        appendScript(
            $$"""
            pid="$(cat $${pidPath.shellQuote()} 2>/dev/null || true)"
            [ -n "$pid" ] || exit 1
            """,
        )
        append(buildRootProcessMatchTest(executablePath, uid, gid))
    }
}

internal fun RootRuntimeLayout.buildRootConfigTagMatchCommand(tag: String): String {
    return "grep -F ${tag.shellQuote()} ${configPath.shellQuote()} >/dev/null 2>&1"
}

internal fun buildRootProcessMatchTest(
    executablePath: String,
    uid: Int,
    gid: Int,
): String {
    return $$"""
        (
          kill -0 "$pid" 2>/dev/null || exit 1
          cmdline="$(tr '\0' ' ' < /proc/"$pid"/cmdline 2>/dev/null || true)"
          exe="$(readlink /proc/"$pid"/exe 2>/dev/null || true)"
          matched=0
          case "$cmdline" in *$${executablePath.shellQuoteForCase()}*) matched=1;; esac
          [ "$exe" = $${executablePath.shellQuote()} ] && matched=1
          [ "$matched" = 1 ] || exit 1
          uid_line="$(grep '^Uid:' /proc/"$pid"/status 2>/dev/null || true)"
          set -- $uid_line
          [ "$3" = "$$uid" ] || [ "$5" = "$$uid" ] || exit 1
          gid_line="$(grep '^Gid:' /proc/"$pid"/status 2>/dev/null || true)"
          set -- $gid_line
          [ "$3" = "$$gid" ] || [ "$5" = "$$gid" ] || exit 1
        )
    """.trimIndent() + "\n"
}
