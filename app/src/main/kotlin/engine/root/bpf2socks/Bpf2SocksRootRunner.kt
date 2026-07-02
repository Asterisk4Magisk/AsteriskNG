// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.bpf2socks

import engine.root.RootBpf2SocksPinnedObjectDir
import engine.root.RootBpf2SocksFwmark
import engine.root.RootBpf2SocksPreroutingChain
import engine.root.RootBpf2SocksRouteTable
import engine.root.RootBpf2SocksTokenIpv6Prefix
import engine.root.RootIpCommand
import engine.root.RootIp6Command
import engine.root.RootIp6tablesCommand
import engine.root.RootIptablesCommand
import engine.root.RootModeRunner
import engine.root.RootProxyRouteRulePriority
import engine.root.RootReadinessCheck
import engine.root.RootRuntimeLayout
import engine.root.RootXrayGid
import engine.root.RootXrayUid
import engine.root.appendDeleteRuleLoop
import engine.root.appendIpRuleDeleteLoop
import engine.root.appendScript
import engine.root.buildApplyRootEbpfSelinuxPolicyCommand
import engine.root.buildRootPortReadyCommand
import engine.root.normalizeRootEbpfDirectCidrs
import engine.root.toNetstatPortHexMarker
import engine.root.bpf2socksConfigPath
import engine.root.bpf2socksPidPath
import engine.root.rootEbpfDirectCidrPathV4
import engine.root.rootEbpfDirectCidrPathV6
import engine.xray.XrayTags
import features.resources.runtime.writeAtomically
import system.AndroidRootShellGateway
import system.ShellExecOptions
import utils.shellQuote
import java.io.File

internal class Bpf2SocksRootRunner(
    rootAccess: AndroidRootShellGateway,
) : RootModeRunner<Bpf2SocksStartConfig>(
    rootAccess = rootAccess,
    modeName = "BPF2SOCKS",
    runtimeConfigTag = XrayTags.BPF2SOCKS_INBOUND,
    logTag = LogTag,
) {
    override fun buildSetupRulesCommand(config: Bpf2SocksStartConfig): String {
        return buildString {
            append(buildApplyRootEbpfSelinuxPolicyCommand())
            appendBpf2SocksIpv6TokenRouteSetup(config.bpf2socksConfig)
            appendBpf2SocksHotspotSetupRules(config.bpf2socksConfig)
        }
    }

    override fun buildCleanupRulesCommand(): String {
        return buildString {
            appendBpf2SocksHotspotCleanupRules()
            appendBpf2SocksIpv6TokenRouteCleanup()
            appendScript("rm -rf ${RootBpf2SocksPinnedObjectDir.shellQuote()} 2>/dev/null || true")
        }
    }

    override suspend fun prepareModeRuntimeFiles(config: Bpf2SocksStartConfig) {
        writeDirectCidrs(config.root.runtimeLayout.rootEbpfDirectCidrPathV4, config.directCidrSourcePathsV4, ipv6 = false)
        writeDirectCidrs(config.root.runtimeLayout.rootEbpfDirectCidrPathV6, config.directCidrSourcePathsV6, ipv6 = true)
        writeAtomically(File(config.controlPaths.configPath)) { output ->
            output.write(config.bpf2socksConfig.toJsonString().toByteArray(Charsets.UTF_8))
        }
    }

    override fun buildPostCoreStartCommand(config: Bpf2SocksStartConfig): String {
        val helper = config.controlPaths
        val setuidgid = config.root.setuidgidPath
        return buildString {
            appendScript(
                $$"""
                chmod 755 $${setuidgid.shellQuote()}
                chmod 755 $${helper.executablePath.shellQuote()}
                $${setuidgid.shellQuote()} $${RootXrayUid.toString().shellQuote()} $${RootXrayGid.toString().shellQuote()} $${helper.executablePath.shellQuote()} --start --config $${helper.configPath.shellQuote()} --pid $${helper.pidPath.shellQuote()} >> $${config.root.coreLogPaths.errorLogPath.shellQuote()} 2>&1 < /dev/null &
                echo $! > $${helper.pidPath.shellQuote()}
                """,
            )
        }
    }

    override fun buildPostCoreStopCommand(runtimeLayout: RootRuntimeLayout): String {
        return runtimeLayout.buildStopBpf2SocksCommand()
    }

    override suspend fun isModeRuntimeRunning(config: Bpf2SocksStartConfig): Boolean {
        val command = buildBpf2SocksProcessMatchCommand(config.controlPaths.pidPath)
        return rootAccess.exec(command, ShellExecOptions(logFailure = false)).errno == 0
    }

    override suspend fun isRunning(runtimeLayout: RootRuntimeLayout): Boolean {
        if (!super.isRunning(runtimeLayout)) {
            return false
        }
        return rootAccess.exec(runtimeLayout.buildBpf2SocksRuntimeReadyCommand(), ShellExecOptions(logFailure = false)).errno == 0
    }

    override fun buildReadinessCheck(config: Bpf2SocksStartConfig): RootReadinessCheck {
        val bridgePort = config.bpf2socksConfig.bridgePort
        return RootReadinessCheck(
            description = "bpf2socks bridge port $bridgePort",
            command = buildRootPortReadyCommand(bridgePort),
            failureMessage = "Xray-core started but bpf2socks bridge port $bridgePort is not ready",
        )
    }

    override suspend fun collectReadinessDiagnostics(config: Bpf2SocksStartConfig): String {
        val bridgePort = config.bpf2socksConfig.bridgePort
        val portHex = bridgePort.toNetstatPortHexMarker()
        val fwmark = RootBpf2SocksFwmark.substringBefore('/')
        val command = $$"""
            pid="$(cat $${config.controlPaths.pidPath.shellQuote()} 2>/dev/null || true)"
            echo "== bpf2socks =="
            echo "pid=$pid"
            if [ -n "$pid" ]; then
                echo "cmdline=$(tr '\0' ' ' < /proc/"$pid"/cmdline 2>/dev/null || true)"
                grep -E '^(Uid|Gid):' /proc/"$pid"/status 2>/dev/null || true
            fi
            echo "== netstat =="
            netstat -an 2>&1 | head -n 40 || true
            echo "bridgePort=$${bridgePort}"
            echo "bridgePortHex=$${portHex}"
            echo "== bpf2socks pinned objects =="
            ls -la $${RootBpf2SocksPinnedObjectDir.shellQuote()} 2>&1 || true
            echo "== bpf2socks hotspot rules =="
            $${RootIpCommand} rule show 2>&1 | grep -E 'fwmark $${fwmark}' || true
            $${RootIpCommand} route show table $${RootBpf2SocksRouteTable} 2>&1 || true
            $${RootIptablesCommand} -t mangle -S $${RootBpf2SocksPreroutingChain} 2>&1 || true
            echo "== core error log =="
            tail -n 80 $${config.root.coreLogPaths.errorLogPath.shellQuote()} 2>&1 || true
        """.trimIndent()
        val result = rootAccess.exec(command, ShellExecOptions(logFailure = false))
        return result.stdout.ifBlank { result.stderr }
    }

    override fun StringBuilder.appendStartupSummary(config: Bpf2SocksStartConfig) {
        appendScript("echo \"bpf2socks bridge port: ${config.bpf2socksConfig.bridgePort}\"")
        appendScript("echo \"bpf2socks config: ${config.controlPaths.configPath.shellQuote()}\"")
        appendScript("echo \"bpf2socks hotspot interfaces: ${config.bpf2socksConfig.hotspotInterfacePrefixes.joinToString(",").ifBlank { "disabled" }.shellQuote()}\"")
    }

    override fun StringBuilder.appendStartupFailureDiagnostics(config: Bpf2SocksStartConfig) {
        val fwmark = RootBpf2SocksFwmark.substringBefore('/')
        appendScript(
            $$"""
                echo
                echo "bpf2socks probe:"
                $${config.root.setuidgidPath.shellQuote()} $${RootXrayUid.toString().shellQuote()} $${RootXrayGid.toString().shellQuote()} $${config.controlPaths.executablePath.shellQuote()} --probe --config $${config.controlPaths.configPath.shellQuote()} 2>&1 || true
                echo
                echo "bpf2socks process:"
                pid="$(cat $${config.controlPaths.pidPath.shellQuote()} 2>/dev/null || true)"
                [ -n "$pid" ] && tr '\0' ' ' < /proc/"$pid"/cmdline 2>/dev/null || true
                echo
                echo "bpf2socks pinned objects:"
                ls -la $${RootBpf2SocksPinnedObjectDir.shellQuote()} 2>&1 || true
                echo
                echo "bpf2socks hotspot rules:"
                $${RootIpCommand} rule show 2>&1 | grep -E 'fwmark $${fwmark}' || true
                $${RootIpCommand} route show table $${RootBpf2SocksRouteTable} 2>&1 || true
                $${RootIptablesCommand} -t mangle -S $${RootBpf2SocksPreroutingChain} 2>&1 || true
            """,
        )
    }

    private fun writeDirectCidrs(path: String, sourcePaths: List<String>, ipv6: Boolean) {
        val directCidrs = buildList {
            sourcePaths.forEach { sourcePath ->
                val file = File(sourcePath)
                if (file.isFile) {
                    addAll(file.readLines())
                }
            }
        }.let { lines ->
            normalizeRootEbpfDirectCidrs(lines).cidrs.filter { cidr -> (":" in cidr) == ipv6 }
        }
        writeAtomically(File(path)) { output ->
            output.write((directCidrs.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
        }
    }

    private companion object {
        private const val LogTag = "Bpf2SocksRootRunner"
    }
}

private fun StringBuilder.appendBpf2SocksHotspotSetupRules(config: Bpf2SocksConfig) {
    val prefixes = config.hotspotInterfacePrefixes
    if (prefixes.isEmpty()) return
    appendBpf2SocksHotspotCleanupRules()
    appendScript(
        """
        $RootIpCommand rule add priority $RootProxyRouteRulePriority fwmark $RootBpf2SocksFwmark table $RootBpf2SocksRouteTable 2>/dev/null || true
        $RootIpCommand route add local default dev lo table $RootBpf2SocksRouteTable 2>/dev/null || true
        $RootIptablesCommand -t mangle -N $RootBpf2SocksPreroutingChain 2>/dev/null || true
        $RootIptablesCommand -t mangle -I PREROUTING 1 -j $RootBpf2SocksPreroutingChain
        """,
    )
    val programPath = config.preroutingPolicyIpv4Path.shellQuote()
    prefixes.forEach { prefix ->
        val quotedInterface = prefix.shellQuote()
        appendScript(
            """
            $RootIptablesCommand -t mangle -A $RootBpf2SocksPreroutingChain -i $quotedInterface -p tcp -m bpf --object-pinned $programPath -j MARK --set-xmark $RootBpf2SocksFwmark
            $RootIptablesCommand -t mangle -A $RootBpf2SocksPreroutingChain -i $quotedInterface -p udp -m bpf --object-pinned $programPath -j MARK --set-xmark $RootBpf2SocksFwmark
            """,
        )
    }
    if (config.enableIpv6) {
        appendScript(
            """
            $RootIp6Command rule add priority $RootProxyRouteRulePriority fwmark $RootBpf2SocksFwmark table $RootBpf2SocksRouteTable 2>/dev/null || true
            $RootIp6Command route add local default dev lo table $RootBpf2SocksRouteTable 2>/dev/null || true
            $RootIp6tablesCommand -t mangle -N $RootBpf2SocksPreroutingChain 2>/dev/null || true
            $RootIp6tablesCommand -t mangle -I PREROUTING 1 -j $RootBpf2SocksPreroutingChain
            """,
        )
        val programPath6 = config.preroutingPolicyIpv6Path.shellQuote()
        prefixes.forEach { prefix ->
            val quotedInterface = prefix.shellQuote()
            appendScript(
                """
                $RootIp6tablesCommand -t mangle -A $RootBpf2SocksPreroutingChain -i $quotedInterface -p tcp -m bpf --object-pinned $programPath6 -j MARK --set-xmark $RootBpf2SocksFwmark
                $RootIp6tablesCommand -t mangle -A $RootBpf2SocksPreroutingChain -i $quotedInterface -p udp -m bpf --object-pinned $programPath6 -j MARK --set-xmark $RootBpf2SocksFwmark
                """,
            )
        }
    }
}

private fun StringBuilder.appendBpf2SocksIpv6TokenRouteSetup(config: Bpf2SocksConfig) {
    if (!config.enableIpv6) return
    appendScript(
        """
        $RootIp6Command route replace local ${config.tokenIpv6Prefix.shellQuote()} dev lo table local 2>/dev/null || true
        """,
    )
}

private fun StringBuilder.appendBpf2SocksIpv6TokenRouteCleanup() {
    appendScript(
        """
        $RootIp6Command route del local ${RootBpf2SocksTokenIpv6Prefix.shellQuote()} dev lo table local 2>/dev/null || true
        """,
    )
}

private fun StringBuilder.appendBpf2SocksHotspotCleanupRules() {
    appendDeleteRuleLoop(RootIptablesCommand, "PREROUTING", "-j $RootBpf2SocksPreroutingChain")
    appendDeleteRuleLoop(RootIp6tablesCommand, "PREROUTING", "-j $RootBpf2SocksPreroutingChain")
    appendScript(
        """
        $RootIptablesCommand -t mangle -F $RootBpf2SocksPreroutingChain 2>/dev/null || true
        $RootIptablesCommand -t mangle -X $RootBpf2SocksPreroutingChain 2>/dev/null || true
        $RootIp6tablesCommand -t mangle -F $RootBpf2SocksPreroutingChain 2>/dev/null || true
        $RootIp6tablesCommand -t mangle -X $RootBpf2SocksPreroutingChain 2>/dev/null || true
        """,
    )
    appendIpRuleDeleteLoop(
        ipCommand = RootIpCommand,
        rule = "priority $RootProxyRouteRulePriority fwmark $RootBpf2SocksFwmark table $RootBpf2SocksRouteTable",
    )
    appendScript("$RootIpCommand route flush table $RootBpf2SocksRouteTable 2>/dev/null || true")
    appendIpRuleDeleteLoop(
        ipCommand = RootIp6Command,
        rule = "priority $RootProxyRouteRulePriority fwmark $RootBpf2SocksFwmark table $RootBpf2SocksRouteTable",
    )
    appendScript("$RootIp6Command route flush table $RootBpf2SocksRouteTable 2>/dev/null || true")
}

internal fun RootRuntimeLayout.buildStopBpf2SocksCommand(): String {
    return buildString {
        appendScript(
            $$"""
            if [ -x $${bpf2socksPath.shellQuote()} ]; then
                $${bpf2socksPath.shellQuote()} --stop --config $${bpf2socksConfigPath.shellQuote()} --pid $${bpf2socksPidPath.shellQuote()} >/dev/null 2>&1 || true
            fi
            pid="$(cat $${bpf2socksPidPath.shellQuote()} 2>/dev/null || true)"
            if [ -n "$pid" ]; then
                kill "$pid" 2>/dev/null || true
                sleep 0.2
                kill -9 "$pid" 2>/dev/null || true
            fi
            stale_bpf2socks_pids=""
            if command -v pidof >/dev/null 2>&1; then
                bpf2socks_pid_candidates="$(pidof libbpf2socks.so bpf2socks 2>/dev/null || true)"
            else
                bpf2socks_pid_candidates=""
                for bpf2socks_pid_dir in /proc/[0-9]*; do
                    bpf2socks_pid_candidates="$bpf2socks_pid_candidates ${bpf2socks_pid_dir##*/}"
                done
            fi
            for bpf2socks_pid in $bpf2socks_pid_candidates; do
                bpf2socks_pid_dir="/proc/$bpf2socks_pid"
                [ -d "$bpf2socks_pid_dir" ] || continue
                bpf2socks_pid="${bpf2socks_pid_dir##*/}"
                [ -n "$bpf2socks_pid" ] || continue
                cmdline="$(tr '\0' ' ' < "$bpf2socks_pid_dir/cmdline" 2>/dev/null || true)"
                exe="$(readlink "$bpf2socks_pid_dir/exe" 2>/dev/null || true)"
                argv0="${cmdline%% *}"
                argv0_base="${argv0##*/}"
                exe_base="${exe##*/}"
                matched=0
                case "$argv0_base" in bpf2socks|libbpf2socks.so) matched=1;; esac
                case "$exe_base" in bpf2socks|libbpf2socks.so) matched=1;; esac
                [ "$matched" = 1 ] || continue
                uid_line="$(grep '^Uid:' "$bpf2socks_pid_dir/status" 2>/dev/null || true)"
                set -- $uid_line
                [ "$3" = "$${RootXrayUid}" ] || [ "$5" = "$${RootXrayUid}" ] || continue
                gid_line="$(grep '^Gid:' "$bpf2socks_pid_dir/status" 2>/dev/null || true)"
                set -- $gid_line
                [ "$3" = "$${RootXrayGid}" ] || [ "$5" = "$${RootXrayGid}" ] || continue
                kill "$bpf2socks_pid" 2>/dev/null || true
                stale_bpf2socks_pids="$stale_bpf2socks_pids $bpf2socks_pid"
            done
            if [ -n "$stale_bpf2socks_pids" ]; then
                sleep 0.2
                for bpf2socks_pid in $stale_bpf2socks_pids; do
                    kill -9 "$bpf2socks_pid" 2>/dev/null || true
                done
            fi
            rm -f $${bpf2socksPidPath.shellQuote()} 2>/dev/null || true
            rm -rf $${RootBpf2SocksPinnedObjectDir.shellQuote()} 2>/dev/null || true
            """,
        )
    }
}

private fun RootRuntimeLayout.buildBpf2SocksRuntimeReadyCommand(): String {
    return buildBpf2SocksProcessMatchCommand(bpf2socksPidPath)
}

private fun buildBpf2SocksProcessMatchCommand(pidPath: String): String {
    return buildString {
        appendScript(
            $$"""
            pid="$(cat $${pidPath.shellQuote()} 2>/dev/null || true)"
            [ -n "$pid" ] || exit 1
            (
              kill -0 "$pid" 2>/dev/null || exit 1
              cmdline="$(tr '\0' ' ' < /proc/"$pid"/cmdline 2>/dev/null || true)"
              exe="$(readlink /proc/"$pid"/exe 2>/dev/null || true)"
              argv0="${cmdline%% *}"
              argv0_base="${argv0##*/}"
              exe_base="${exe##*/}"
              matched=0
              case "$argv0_base" in bpf2socks|libbpf2socks.so) matched=1;; esac
              case "$exe_base" in bpf2socks|libbpf2socks.so) matched=1;; esac
              [ "$matched" = 1 ] || exit 1
              uid_line="$(grep '^Uid:' /proc/"$pid"/status 2>/dev/null || true)"
              set -- $uid_line
              [ "$3" = "$${RootXrayUid}" ] || [ "$5" = "$${RootXrayUid}" ] || exit 1
              gid_line="$(grep '^Gid:' /proc/"$pid"/status 2>/dev/null || true)"
              set -- $gid_line
              [ "$3" = "$${RootXrayGid}" ] || [ "$5" = "$${RootXrayGid}" ] || exit 1
            )
            """,
        )
    }
}
