// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.tun2socks

import engine.root.RootModeRunner
import engine.root.RootReadinessCheck
import engine.root.RootRuntimeLayout
import engine.root.appendScript
import engine.root.buildRootPortReadyCommand
import engine.root.toNetstatPortHexMarker
import engine.xray.XrayTags
import system.AndroidRootShellGateway
import system.ShellExecOptions
import utils.shellQuote

internal class Tun2SocksRootRunner(
    rootAccess: AndroidRootShellGateway,
) : RootModeRunner<Tun2SocksStartConfig>(
    rootAccess = rootAccess,
    modeName = "TUN2SOCKS",
    runtimeConfigTag = XrayTags.TUN2SOCKS_INBOUND,
    logTag = LogTag,
) {
    override fun buildSetupRulesCommand(config: Tun2SocksStartConfig): String {
        return config.iptablesConfig.buildSetupRulesCommand(
            enableIpv6 = config.root.enableIpv6,
            enableLocalDns = config.root.enableLocalDns,
            enableFakeDns = config.root.enableFakeDns,
        )
    }

    override fun buildCleanupRulesCommand(): String {
        return Tun2SocksBaseIptablesConfig.buildCleanupRulesCommand()
    }

    override fun buildPostCoreStartCommand(config: Tun2SocksStartConfig): String {
        return config.hevSocks5TunnelConfig.buildStartCommand()
    }

    override suspend fun prepareModeRuntimeFiles(config: Tun2SocksStartConfig) {
        config.hevSocks5TunnelConfig.writeConfigFile()
    }

    override fun buildPostCoreStopCommand(runtimeLayout: RootRuntimeLayout): String {
        return runtimeLayout.buildStopHevSocks5TunnelCommand()
    }

    override suspend fun isModeRuntimeRunning(config: Tun2SocksStartConfig): Boolean {
        val result = rootAccess.exec(config.hevSocks5TunnelConfig.buildProcessMatchCommand(), ShellExecOptions(logFailure = false))
        return result.errno == 0
    }

    override suspend fun isRunning(runtimeLayout: RootRuntimeLayout): Boolean {
        if (!super.isRunning(runtimeLayout)) {
            return false
        }
        val result = rootAccess.exec(runtimeLayout.buildHevSocks5TunnelRuntimeReadyCommand(), ShellExecOptions(logFailure = false))
        return result.errno == 0
    }

    override fun buildReadinessCheck(config: Tun2SocksStartConfig): RootReadinessCheck {
        val socksPort = config.hevSocks5TunnelConfig.socksPort
        return RootReadinessCheck(
            description = "SOCKS5 port $socksPort and TUN device asterisk0",
            command = buildRootPortReadyCommand(socksPort) +
                " && ip link show dev 'asterisk0' >/dev/null 2>&1",
            failureMessage = "Xray-core started but SOCKS5 port $socksPort or TUN device asterisk0 is not ready",
        )
    }

    override suspend fun collectReadinessDiagnostics(config: Tun2SocksStartConfig): String {
        val socksPort = config.hevSocks5TunnelConfig.socksPort
        val portHex = socksPort.toNetstatPortHexMarker()
        val command = $$"""
            pid="$(cat $${config.root.runtimeLayout.pidPath.shellQuote()} 2>/dev/null || true)"
            echo "== netstat =="
            netstat -an 2>&1 | head -n 40 || true
            echo "socksPort=$$socksPort"
            echo "socksPortHex=$$portHex"
            if [ -n "$pid" ]; then
                for proc_file in /proc/"$pid"/net/tcp6 /proc/"$pid"/net/tcp; do
                    echo "== $proc_file =="
                    head -n 12 "$proc_file" 2>&1 || true
                done
            fi
            echo "== ip link =="
            ip link show dev 'asterisk0' 2>&1 || true
            echo "== ip addr =="
            ip addr show dev 'asterisk0' 2>&1 || true
            echo "== ip rule =="
            ip rule 2>&1 | head -n 40 || true
            ip -6 rule 2>&1 | head -n 40 || true
            echo "== tun2socks log =="
            tail -n 80 $${config.hevSocks5TunnelConfig.logPath.shellQuote()} 2>&1 || true
        """.trimIndent()
        val result = rootAccess.exec(command, ShellExecOptions(logFailure = false))
        return result.stdout.ifBlank { result.stderr }
    }

    override fun StringBuilder.appendStartupSummary(config: Tun2SocksStartConfig) {
        appendScript("echo \"SOCKS5 port: ${config.hevSocks5TunnelConfig.socksPort}\"")
        appendScript("echo \"TUN device: asterisk0\"")
        appendScript("echo \"tun2socks config: ${config.hevSocks5TunnelConfig.configPath.shellQuote()}\"")
        appendScript("echo \"tun2socks log: ${config.hevSocks5TunnelConfig.logPath.shellQuote()}\"")
    }

    override fun StringBuilder.appendStartupFailureDiagnostics(config: Tun2SocksStartConfig) {
        appendScript(
            $$"""
                echo
                echo "SOCKS5 port snapshot:"
                netstat -an 2>&1 | grep "[.:]$${config.hevSocks5TunnelConfig.socksPort}[[:space:]]" || true
                echo
                echo "TUN device snapshot:"
                ip link show dev 'asterisk0' 2>&1 || true
                ip addr show dev 'asterisk0' 2>&1 || true
                echo
                echo "Routing rule snapshot:"
                ip rule 2>&1 | head -n 40 || true
                ip -6 rule 2>&1 | head -n 40 || true
                echo
                echo "tun2socks log:"
                tail -n 80 $${config.hevSocks5TunnelConfig.logPath.shellQuote()} || true
            """,
        )
    }

    private companion object {
        private const val LogTag = "Tun2SocksRootRunner"
    }
}
