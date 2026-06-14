// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root

import android.content.Context
import engine.xray.XrayCoreLogPaths
import engine.xray.logDirectoryPath
import engine.xray.prepareXrayCoreLogPaths
import utils.shellQuote
import java.io.File

internal val RootStartConfig.shouldStartIpv6Disabler: Boolean
    get() = !enableIpv6 && enableRootIpv6Disabler

internal fun RootStartConfig.buildStartIpv6DisablerCommand(): String {
    if (!shouldStartIpv6Disabler) {
        return ""
    }
    return runtimeLayout.buildStartIpv6DisablerCommand(ipv6DisablerLogPath)
}

internal fun RootRuntimeLayout.buildStartIpv6DisablerCommand(logPath: String): String {
    val logDirPath = File(logPath).parentFile?.absolutePath
    val processMatchTest = buildIpv6DisablerProcessMatchTest().trimEnd()
    return buildString {
        logDirPath?.let { path ->
            appendScript("mkdir -p ${path.shellQuote()} || exit 1")
        }
        appendScript(
            $$"""
            mkdir -p $${dataDir.shellQuote()} || exit 1
            rm -f $${ipv6DisablerPidPath.shellQuote()} 2>/dev/null || true
            rm -f $${logPath.shellQuote()} 2>/dev/null || true
            touch $${logPath.shellQuote()} || exit 1
            chmod 666 $${logPath.shellQuote()} || exit 1
            chmod 755 $${ipv6DisablerPath.shellQuote()} || exit 1
            $${ipv6DisablerPath.shellQuote()} daemon --pid $${ipv6DisablerPidPath.shellQuote()} --log $${logPath.shellQuote()} >> $${logPath.shellQuote()} 2>&1 < /dev/null &

            ipv6_disabler_ready=0
            ipv6_disabler_attempt=0
            while [ "$ipv6_disabler_attempt" -lt 20 ]; do
                pid="$(cat $${ipv6DisablerPidPath.shellQuote()} 2>/dev/null || true)"
                if [ -n "$pid" ] && $${processMatchTest}; then
                    ipv6_disabler_ready=1
                    break
                fi
                ipv6_disabler_attempt=$((ipv6_disabler_attempt + 1))
                sleep 0.1
            done
            if [ "$ipv6_disabler_ready" != "1" ]; then
                echo "IPv6 disabler failed to start" >&2
                tail -n 80 $${logPath.shellQuote()} >&2 || true
                exit 1
            fi
            """,
        )
    }
}

internal fun RootRuntimeLayout.buildStopIpv6DisablerCommand(): String {
    val processMatchTest = buildIpv6DisablerProcessMatchTest().trimEnd()
    return buildString {
        appendScript(
            $$"""
            pid="$(cat $${ipv6DisablerPidPath.shellQuote()} 2>/dev/null || true)"
            if [ -n "$pid" ] && $${processMatchTest}; then
                kill "$pid" 2>/dev/null || true
                ipv6_disabler_attempt=0
                while [ "$ipv6_disabler_attempt" -lt 20 ] && kill -0 "$pid" 2>/dev/null; do
                    ipv6_disabler_attempt=$((ipv6_disabler_attempt + 1))
                    sleep 0.1
                done
                kill -9 "$pid" 2>/dev/null || true
            fi
            rm -f $${ipv6DisablerPidPath.shellQuote()} 2>/dev/null || true
            """,
        )
    }
}

internal fun XrayCoreLogPaths.ipv6DisablerLogFile(): File {
    return File(logDirectoryPath(), RootIpv6DisablerLogFileName)
}

internal fun Context.deleteIpv6DisablerLogFile() {
    val file = applicationContext.prepareXrayCoreLogPaths().ipv6DisablerLogFile()
    if (file.exists() && !file.delete()) {
        error("Failed to delete ${file.absolutePath}")
    }
}

private fun RootRuntimeLayout.buildIpv6DisablerProcessMatchTest(): String {
    return buildRootProcessMatchTest(
        executablePath = ipv6DisablerPath,
        uid = RootIpv6DisablerUid,
        gid = RootIpv6DisablerGid,
    )
}

private const val RootIpv6DisablerUid = 0
private const val RootIpv6DisablerGid = 0
