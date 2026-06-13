// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.tproxy

import engine.root.RootModeRunner
import engine.root.RootReadinessCheck
import engine.root.appendScript
import engine.root.buildRootPortReadyCommand
import engine.root.toNetstatPortHexMarker
import engine.xray.XrayTags
import system.AndroidRootShellGateway
import system.ShellExecOptions
import utils.shellQuote

internal class TproxyRootRunner(
    rootAccess: AndroidRootShellGateway,
) : RootModeRunner<TproxyStartConfig>(
    rootAccess = rootAccess,
    modeName = "TPROXY",
    runtimeConfigTag = XrayTags.TPROXY_INBOUND,
    logTag = LogTag,
) {
    override fun buildSetupRulesCommand(config: TproxyStartConfig): String {
        return config.iptablesConfig.buildSetupRulesCommand(
            port = config.tproxyPort,
            enableIpv6 = config.root.enableIpv6,
            enableFakeDns = config.root.enableFakeDns,
        )
    }

    override fun buildCleanupRulesCommand(): String {
        return TproxyBaseIptablesConfig.buildCleanupRulesCommand()
    }

    override fun buildReadinessCheck(config: TproxyStartConfig): RootReadinessCheck {
        return RootReadinessCheck(
            description = "tproxy-in port ${config.tproxyPort}",
            command = buildRootPortReadyCommand(config.tproxyPort),
            failureMessage = "Xray-core started but tproxy-in port ${config.tproxyPort} is not ready",
        )
    }

    override suspend fun collectReadinessDiagnostics(config: TproxyStartConfig): String {
        val portHex = config.tproxyPort.toNetstatPortHexMarker()
        val command = $$"""
            pid="$(cat $${config.root.runtimeLayout.pidPath.shellQuote()} 2>/dev/null || true)"
            echo "== netstat =="
            netstat -an 2>&1 | head -n 40 || true
            echo "portHex=$$portHex"
            if [ -n "$pid" ]; then
                for proc_file in /proc/"$pid"/net/tcp6 /proc/"$pid"/net/tcp; do
                    echo "== $proc_file =="
                    head -n 12 "$proc_file" 2>&1 || true
                done
            fi
        """.trimIndent()
        val result = rootAccess.exec(command, ShellExecOptions(logFailure = false))
        return result.stdout.ifBlank { result.stderr }
    }

    override fun StringBuilder.appendStartupSummary(config: TproxyStartConfig) {
        appendScript("echo \"TPROXY port: ${config.tproxyPort}\"")
    }

    override fun StringBuilder.appendStartupFailureDiagnostics(config: TproxyStartConfig) {
        appendScript(
            """
                echo
                echo "netstat snapshot:"
                netstat -an || true
            """,
        )
    }

    private companion object {
        private const val LogTag = "TproxyRootRunner"
    }
}
