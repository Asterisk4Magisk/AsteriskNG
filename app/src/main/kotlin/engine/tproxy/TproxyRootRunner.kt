// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.tproxy

import engine.xray.XrayCoreLogPaths
import engine.xray.logFilePaths
import features.logs.AndroidAppLogger
import features.resources.runtime.writeAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import system.AndroidRootShellGateway
import system.ShellExecOptions
import java.io.File

internal class TproxyRootRunner(
    private val rootAccess: AndroidRootShellGateway,
) {
    suspend fun start(config: TproxyStartConfig) = withContext(Dispatchers.IO) {
        writeRuntimeFiles(config)
        stop(config)
        runRootCommand(config.prepareStartCommand(), "Failed to prepare TPROXY environment")
        runRootCommand(config.startDetachedCommand(), "Failed to start xray-core daemon")

        if (!config.waitForTproxyInbound()) {
            failStartup(
                config,
                "xray-core started but tproxy-in port ${config.tproxyPort} is not ready"
            )
        }
        if (!isRunning(config.runtime.pidPath, config.runtime.xrayCorePath)) {
            failStartup(
                config,
                "xray-core process exited or did not match the expected TPROXY runtime state"
            )
        }

        runRootCommand(config.installTproxyRulesCommand(), "Failed to install TPROXY rules")
    }

    suspend fun stop(
        config: TproxyStartConfig?,
        fallbackRuntime: TproxyRuntimePaths? = null,
    ) = withContext(Dispatchers.IO) {
        val command = config?.stopCommand()
            ?: TproxyIptablesConfig().stopCommand(fallbackRuntime)
        val result = rootAccess.exec(command, ShellExecOptions(logFailure = false))
        if (result.errno != 0) {
            AndroidAppLogger.warn(LogTag, "Failed to stop TPROXY cleanly:\n${result.stderr}")
        }
    }

    suspend fun installBootScript(config: TproxyStartConfig) = withContext(Dispatchers.IO) {
        writeRuntimeFiles(config)
        runRootCommand(config.installBootScriptCommand(), "Failed to install TPROXY boot script")
    }

    suspend fun prepareCoreLogFiles(coreLogPaths: XrayCoreLogPaths) = withContext(Dispatchers.IO) {
        val command = coreLogPaths.prepareWritableCoreLogFilesCommand()
        if (command.isBlank()) {
            return@withContext
        }
        runRootCommand(command, "Failed to prepare xray log files")
    }

    suspend fun uninstallBootScript(runtime: TproxyRuntimePaths) = withContext(Dispatchers.IO) {
        val command = buildString {
            appendScript("rm -f ${TproxyBootScriptPath.shellQuote()} 2>/dev/null || true")
            appendScript("rm -f ${runtime.bootstrapScriptPath.shellQuote()} 2>/dev/null || true")
            appendScript("rm -f ${runtime.bootLogPath.shellQuote()} 2>/dev/null || true")
        }
        runRootCommand(command, "Failed to remove TPROXY boot script")
    }

    suspend fun isRunning(
        pidPath: String,
        xrayCorePath: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val command = buildProcessMatchCommand(
            pidPath = pidPath,
            xrayCorePath = xrayCorePath,
            uid = TproxyBypassUid,
            gid = TproxyBypassGid,
        )
        rootAccess.exec(command, ShellExecOptions(logFailure = false)).errno == 0
    }

    private fun writeRuntimeFiles(config: TproxyStartConfig) {
        writeAtomically(File(config.configPath)) { output ->
            output.write(config.xrayConfigJson.toByteArray(Charsets.UTF_8))
        }
    }

    private suspend fun runRootCommand(command: String, failureMessage: String) {
        val result = rootAccess.exec(command, ShellExecOptions(logFailure = false))
        if (result.errno != 0) {
            error(
                diagnosticMessage(
                    failureMessage,
                    result.stderr,
                    result.stdout,
                ),
            )
        }
    }

    private fun TproxyStartConfig.prepareStartCommand(): String {
        return buildString {
            appendScript(
                """
                rm -f ${runtime.pidPath.shellQuote()} 2>/dev/null || true
                chmod 755 ${runtime.xrayCorePath.shellQuote()}
                """,
            )
        }
    }

    private fun TproxyStartConfig.installTproxyRulesCommand(): String {
        return iptablesConfig.setupCommand(port = tproxyPort, enableIpv6 = enableIpv6)
    }

    private fun TproxyStartConfig.stopCommand(): String {
        return iptablesConfig.stopCommand(
            runtime = runtime,
        )
    }

    private fun TproxyIptablesConfig.stopCommand(runtime: TproxyRuntimePaths?): String {
        return buildString {
            runtime?.let { paths ->
                appendScript(
                    $$"""
                    pid="$(cat $${paths.pidPath.shellQuote()} 2>/dev/null || true)"
                    if [ -n "$pid" ] && $${processMatchTest(paths.xrayCorePath, uid, gid).trimEnd()}; then
                        kill "$pid" 2>/dev/null || true
                        sleep 0.2
                        kill -9 "$pid" 2>/dev/null || true
                    fi
                    rm -f $${paths.pidPath.shellQuote()} 2>/dev/null || true
                    """,
                )
            }
            append(cleanupCommand())
        }
    }

    private fun TproxyStartConfig.startDetachedCommand(): String {
        val uid = iptablesConfig.uid.toString()
        val gid = iptablesConfig.gid.toString()
        return buildString {
            appendScript(
                $$"""
                trap '' HUP
                cd $${runtime.dataDir.shellQuote()} || exit 1
                export XRAY_LOCATION_ASSET=$${runtime.dataDir.shellQuote()}
                ulimit -SHn 1000000 2>/dev/null || true
                chmod 755 $${setuidgidPath.shellQuote()}
                $${setuidgidPath.shellQuote()} $${uid.shellQuote()} $${gid.shellQuote()} $${runtime.xrayCorePath.shellQuote()} run -config $${configPath.shellQuote()} >> $${coreLogPaths.errorLogPath.shellQuote()} 2>&1 < /dev/null &
                echo $! > $${runtime.pidPath.shellQuote()}
                """,
            )
        }
    }

    private fun TproxyStartConfig.startDetachedBootCommand(): String {
        val uid = iptablesConfig.uid.toString()
        val gid = iptablesConfig.gid.toString()
        return buildString {
            appendScript(
                $$"""
                trap '' HUP
                cd $${runtime.dataDir.shellQuote()} || exit 1
                export XRAY_LOCATION_ASSET=$${runtime.dataDir.shellQuote()}
                ulimit -SHn 1000000 || true
                chmod 755 $${setuidgidPath.shellQuote()}
                echo "+ start xray-core as uid=$$uid gid=$$gid"
                echo "+ xray stdout/stderr: $${coreLogPaths.errorLogPath.shellQuote()}"
                $${setuidgidPath.shellQuote()} $${uid.shellQuote()} $${gid.shellQuote()} $${runtime.xrayCorePath.shellQuote()} run -config $${configPath.shellQuote()} >> $${coreLogPaths.errorLogPath.shellQuote()} 2>&1 < /dev/null &
                echo $! > $${runtime.pidPath.shellQuote()}
                echo "xray-core pid: $(cat $${runtime.pidPath.shellQuote()})"
                """,
            )
        }
    }

    private fun TproxyStartConfig.installBootScriptCommand(): String {
        val bootScript = buildBootScript()
        val bootstrapScript = buildBootstrapScript()
        return buildString {
            appendScript("mkdir -p ${TproxyBootScriptDir.shellQuote()}")
            appendScript("mkdir -p ${runtime.dataDir.shellQuote()}")
            appendScript("mkdir -p ${bootLogDirPath.shellQuote()}")
            appendHeredoc(
                targetPath = bootstrapScriptPath,
                delimiter = "ASTERISKNG_TPROXY_BOOTSTRAP_SCRIPT",
                content = bootstrapScript,
            )
            appendScript("chmod 755 ${bootstrapScriptPath.shellQuote()}")
            appendScript("touch ${bootLogPath.shellQuote()}")
            appendScript("chmod 666 ${bootLogPath.shellQuote()}")
            appendHeredoc(
                targetPath = TproxyBootScriptPath,
                delimiter = "ASTERISKNG_TPROXY_BOOT_SCRIPT",
                content = bootScript,
            )
            appendScript("chmod 755 ${TproxyBootScriptPath.shellQuote()}")
        }
    }

    private fun XrayCoreLogPaths.prepareWritableCoreLogFilesCommand(): String {
        val logPaths = logFilePaths().filter(String::isNotBlank)
        return buildString {
            logPaths
                .mapNotNull { logPath -> File(logPath).parent }
                .distinct()
                .forEach { parentPath ->
                    appendScript(
                        """
                        mkdir -p ${parentPath.shellQuote()} || exit 1
                        """,
                    )
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

    private fun TproxyStartConfig.buildBootScript(): String {
        return buildString {
            appendScript(
                $$"""
                # Generated by AsteriskNG. This script is executed by Magisk service.d at boot.

                (
                until [ "$(getprop sys.boot_completed)" = "1" ]; do
                    sleep 1
                done
                until [ -x $${bootstrapScriptPath.shellQuote()} ]; do
                    sleep 1
                done

                $${bootstrapScriptPath.shellQuote()} &> $${bootLogPath.shellQuote()}
                ) &
                """,
            )
        }
    }

    private fun TproxyStartConfig.buildBootstrapScript(): String {
        return buildString {
            appendScript(
                $$"""
                #!/system/bin/sh
                # Generated by AsteriskNG. This script is ASTERISKNG_TPROXY_BOOT_SCRIPT wrapper.

                set -e
                diagnostics_dumped=0

                timestamp() {
                    date '+%Y-%m-%d %H:%M:%S %z' || date
                }

                section() {
                    echo
                    echo "[$(timestamp)] ===== $* ====="
                }

                finish() {
                    rc=$?
                    if [ "$rc" != "0" ] && [ "$diagnostics_dumped" != "1" ]; then
                        dump_failure_diagnostics
                    fi
                    echo
                    if [ "$rc" = "0" ]; then
                        echo "[$(timestamp)] Bootstrap completed successfully"
                    else
                        echo "[$(timestamp)] Bootstrap failed with exit code $rc"
                    fi
                }

                dump_failure_diagnostics() {
                    diagnostics_dumped=1
                    echo
                    echo "Recent xray error log:"
                    tail -n 80 $${coreLogPaths.errorLogPath.shellQuote()} || true
                    echo
                    echo "netstat snapshot:"
                    netstat -an || true
                    echo
                    echo "Process snapshot:"
                    pid="$(cat $${runtime.pidPath.shellQuote()} || true)"
                    echo "pid=$pid"
                    if [ -n "$pid" ]; then
                        echo "cmdline=$(tr '\0' ' ' < /proc/"$pid"/cmdline || true)"
                        echo "exe=$(readlink /proc/"$pid"/exe || true)"
                        grep -E '^(Uid|Gid):' /proc/"$pid"/status || true
                    fi
                }

                trap finish EXIT

                echo "AsteriskNG TPROXY bootstrap"
                echo "Started at: $(timestamp)"
                echo "Data dir: $${runtime.dataDir.shellQuote()}"
                echo "Config: $${configPath.shellQuote()}"
                echo "PID file: $${runtime.pidPath.shellQuote()}"
                echo "TPROXY port: $$tproxyPort"
                echo "IPv6 enabled: $$enableIpv6"
                echo "Access log enabled: $$enableAccessLog"
                echo "Core error log: $${coreLogPaths.errorLogPath.shellQuote()}"
                echo "Core access log: $${coreLogPaths.accessLogPath.shellQuote()}"

                section "Prepare runtime"
                rm -f $${runtime.pidPath.shellQuote()} || true
                chmod 755 $${runtime.xrayCorePath.shellQuote()}
                """,
            )
            appendScript("section \"Prepare core logs\"")
            append(coreLogPaths.prepareWritableCoreLogFilesCommand())
            appendScript("section \"Start xray-core\"")
            append(startDetachedBootCommand())
            appendScript(
                $$"""

                section "Wait for tproxy-in port $$tproxyPort"
                port_ready=0
                attempt=0
                while [ "$attempt" -lt $$BootPortListenCheckAttempts ]; do
                    echo "Attempt $((attempt + 1))/$$BootPortListenCheckAttempts"
                    if netstat -an | grep "[.:]$$tproxyPort[[:space:]]" | grep -E 'LISTEN|UNKNOWN'; then
                        port_ready=1
                        break
                    fi
                    attempt=$((attempt + 1))
                    sleep 1
                done
                if [ "$port_ready" != "1" ]; then
                    echo "ERROR: tproxy-in port $$tproxyPort is not ready" >&2
                    dump_failure_diagnostics
                    exit 1
                fi
                """,
            )
            append('\n')
            appendScript("section \"Install TPROXY rules\"")
            append(installTproxyRulesCommand())
            appendScript(
                """
                section "TPROXY boot setup is ready"
                """,
            )
        }
    }

    private suspend fun TproxyStartConfig.waitForTproxyInbound(): Boolean {
        val checkCommand = checkTproxyInboundCommand()
        val deadline = System.currentTimeMillis() + PortListenCheckTimeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val result = rootAccess.exec(checkCommand, ShellExecOptions(logFailure = false))
            if (result.errno == 0) {
                return true
            }
            delay(PortListenCheckIntervalMillis)
        }
        val result = rootAccess.exec(checkCommand, ShellExecOptions(logFailure = false))
        return result.errno == 0
    }

    private fun TproxyStartConfig.checkTproxyInboundCommand(): String {
        return """
            netstat -an 2>/dev/null | grep "[.:]$tproxyPort[[:space:]]" | grep -E 'LISTEN|UNKNOWN' >/dev/null 2>&1
        """.trimIndent() + "\n"
    }

    private suspend fun failStartup(config: TproxyStartConfig, message: String): Nothing {
        val errorLog = config.tailErrorLog()
        val processDiagnostics = config.processDiagnostics()
        val portDiagnostics = config.portDiagnostics()
        runRootCommand(config.stopCommand(), "Failed to clean up after xray-core startup failure")
        error(
            diagnosticMessage(
                message,
                processDiagnostics,
                portDiagnostics,
                errorLog,
            ),
        )
    }

    private suspend fun TproxyStartConfig.tailErrorLog(): String {
        val command = "tail -n 80 ${coreLogPaths.errorLogPath.shellQuote()} 2>/dev/null || true"
        val result = rootAccess.exec(command)
        return result.stdout.ifBlank { result.stderr }
    }

    private suspend fun TproxyStartConfig.processDiagnostics(): String {
        val command = $$"""
            pid="$(cat $${runtime.pidPath.shellQuote()} 2>/dev/null || true)"
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

    private suspend fun TproxyStartConfig.portDiagnostics(): String {
        val portHex = tproxyPort.toPortHexMarker()
        val command = $$"""
            pid="$(cat $${runtime.pidPath.shellQuote()} 2>/dev/null || true)"
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

    private fun Int.toPortHexMarker(): String {
        return ":${toString(16).uppercase().padStart(4, '0')} "
    }

    private val TproxyStartConfig.bootstrapScriptPath: String
        get() = runtime.bootstrapScriptPath

    private val TproxyStartConfig.bootLogDirPath: String
        get() = runtime.logDirPath

    private val TproxyStartConfig.bootLogPath: String
        get() = File(bootLogDirPath, TproxyBootLogFileName).absolutePath

    private val TproxyRuntimePaths.bootstrapScriptPath: String
        get() = File(dataDir, TproxyBootstrapScriptFileName).absolutePath

    private val TproxyRuntimePaths.bootLogPath: String
        get() = File(logDirPath, TproxyBootLogFileName).absolutePath

    private fun diagnosticMessage(vararg sections: String): String {
        return sections
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString("\n")
    }

    private fun buildProcessMatchCommand(
        pidPath: String,
        xrayCorePath: String,
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
            append(processMatchTest(xrayCorePath, uid, gid))
        }
    }

    private fun processMatchTest(
        xrayCorePath: String,
        uid: Int,
        gid: Int,
    ): String {
        return $$"""
            (
              kill -0 "$pid" 2>/dev/null || exit 1
              cmdline="$(tr '\0' ' ' < /proc/"$pid"/cmdline 2>/dev/null || true)"
              exe="$(readlink /proc/"$pid"/exe 2>/dev/null || true)"
              matched=0
              case "$cmdline" in *$${xrayCorePath.shellQuoteForCase()}*) matched=1;; esac
              [ "$exe" = $${xrayCorePath.shellQuote()} ] && matched=1
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

    private companion object {
        private const val LogTag = "TproxyRootRunner"
        private const val PortListenCheckTimeoutMillis = 5_000L
        private const val PortListenCheckIntervalMillis = 100L
        private const val BootPortListenCheckAttempts = 5
    }
}
