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
                "xray-core started but tproxy-in port ${config.tproxyPort} is not listening"
            )
        }
        if (!isRunning(config.pidPath, config.xrayCorePath)) {
            failStartup(
                config,
                "xray-core process exited or did not match the expected TPROXY runtime state"
            )
        }

        runRootCommand(config.installTproxyRulesCommand(), "Failed to install TPROXY rules")
    }

    suspend fun stop(
        config: TproxyStartConfig?,
        fallbackRuntimeConfig: TproxyRuntimeConfig? = null,
    ) = withContext(Dispatchers.IO) {
        val command = config?.stopCommand()
            ?: TproxyIptablesConfig().stopCommand(fallbackRuntimeConfig)
        val result = rootAccess.exec(command)
        if (result.errno != 0) {
            AndroidAppLogger.warn(LogTag, "Failed to stop TPROXY cleanly:\n${result.stderr}")
        }
    }

    suspend fun installBootScript(config: TproxyStartConfig) = withContext(Dispatchers.IO) {
        writeRuntimeFiles(config)
        runRootCommand(config.installBootScriptCommand(), "Failed to install TPROXY boot script")
    }

    suspend fun deleteCoreLogFiles(coreLogPaths: XrayCoreLogPaths) = withContext(Dispatchers.IO) {
        val command = coreLogPaths.logFilePaths().deleteCoreLogFilesCommand()
        if (command.isBlank()) {
            return@withContext
        }
        val result = rootAccess.exec(command)
        if (result.errno != 0) {
            AndroidAppLogger.warn(
                LogTag,
                "Failed to delete xray log files as root:\n${result.stderr.ifBlank { result.stdout }}"
            )
        }
    }

    suspend fun truncateCoreLogFiles(logPaths: List<String>) = withContext(Dispatchers.IO) {
        val command = logPaths.truncateCoreLogFilesCommand()
        if (command.isBlank()) {
            return@withContext
        }
        val result = rootAccess.exec(command)
        if (result.errno != 0) {
            AndroidAppLogger.warn(
                LogTag,
                "Failed to truncate xray log files as root:\n${result.stderr.ifBlank { result.stdout }}"
            )
        }
    }

    suspend fun uninstallBootScript() = withContext(Dispatchers.IO) {
        runRootCommand(
            "rm -f ${TproxyBootScriptPath.shellQuote()} 2>/dev/null || true",
            "Failed to remove TPROXY boot script",
        )
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
        val result = rootAccess.exec(command)
        if (result.errno != 0) {
            AndroidAppLogger.error(LogTag, "$failureMessage:\n${result.stderr}")
            error(result.stderr.ifBlank { result.stdout })
        }
    }

    private fun TproxyStartConfig.prepareStartCommand(): String {
        return buildString {
            appendScript(
                """
                rm -f ${pidPath.shellQuote()} 2>/dev/null || true
                chmod 755 ${xrayCorePath.shellQuote()}
                """,
            )
        }
    }

    private fun TproxyStartConfig.installTproxyRulesCommand(): String {
        return iptablesConfig.setupCommand(port = tproxyPort, enableIpv6 = enableIpv6)
    }

    private fun TproxyStartConfig.stopCommand(): String {
        return iptablesConfig.stopCommand(
            runtimeConfig = TproxyRuntimeConfig(
                xrayCorePath = xrayCorePath,
                pidPath = pidPath,
            ),
        )
    }

    private fun TproxyIptablesConfig.stopCommand(runtimeConfig: TproxyRuntimeConfig?): String {
        return buildString {
            runtimeConfig?.let { runtime ->
                appendScript(
                    $$"""
                    pid="$(cat $${runtime.pidPath.shellQuote()} 2>/dev/null || true)"
                    if [ -n "$pid" ] && $${processMatchTest(runtime.xrayCorePath, uid, gid).trimEnd()}; then
                        kill "$pid" 2>/dev/null || true
                        sleep 0.2
                        kill -9 "$pid" 2>/dev/null || true
                    fi
                    rm -f $${runtime.pidPath.shellQuote()} 2>/dev/null || true
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
                cd $${dataDir.shellQuote()} || exit 1
                export XRAY_LOCATION_ASSET=$${dataDir.shellQuote()}
                ulimit -SHn 1000000 2>/dev/null || true
                chmod 755 $${setuidgidPath.shellQuote()}
                $${setuidgidPath.shellQuote()} $${uid.shellQuote()} $${gid.shellQuote()} $${xrayCorePath.shellQuote()} run -config $${configPath.shellQuote()} >> $${coreLogPaths.errorLogPath.shellQuote()} 2>&1 < /dev/null &
                echo $! > $${pidPath.shellQuote()}
                """,
            )
        }
    }

    private fun TproxyStartConfig.installBootScriptCommand(): String {
        val script = buildBootScript()
        return buildString {
            appendScript("mkdir -p ${TproxyBootScriptDir.shellQuote()}")
            appendHeredoc(
                targetPath = TproxyBootScriptPath,
                delimiter = "ASTERISKNG_TPROXY_BOOT_SCRIPT",
                content = script,
            )
            appendScript("chmod 755 ${TproxyBootScriptPath.shellQuote()}")
        }
    }

    private fun List<String>.deleteCoreLogFilesCommand(): String {
        return buildString {
            filter(String::isNotBlank).forEach { logPath ->
                appendScript(
                    """
                    rm -f ${logPath.shellQuote()} 2>/dev/null || true
                    """,
                )
            }
        }
    }

    private fun List<String>.truncateCoreLogFilesCommand(): String {
        return buildString {
            filter(String::isNotBlank).forEach { logPath ->
                File(logPath).parent?.let { parentPath ->
                    appendScript(
                        """
                        : > ${logPath.shellQuote()}
                        """,
                    )
                }
            }
        }
    }

    private fun TproxyStartConfig.buildBootScript(): String {
        return buildString {
            appendScript(
                """
                #!/system/bin/sh
                # Generated by AsteriskNG. This script is executed by Magisk service.d at boot.

                (
                until [ $(getprop sys.boot_completed) -eq 1 ]; do
                    sleep 5
                done
                
                rm -f ${pidPath.shellQuote()} 2>/dev/null || true
                chmod 755 ${xrayCorePath.shellQuote()}

                """,
            )
            append(startDetachedCommand())
            appendScript(
                $$"""

                port_ready=0
                attempt=0
                while [ "$attempt" -lt $$BootPortListenCheckAttempts ]; do
                    if netstat -an 2>/dev/null | grep 'LISTEN' | grep "[.:]$$tproxyPort[[:space:]]" >/dev/null 2>&1; then
                        port_ready=1
                        break
                    fi
                    attempt=$((attempt + 1))
                    sleep 1
                done
                if [ "$port_ready" != "1" ]; then
                    echo "tproxy-in port $$tproxyPort is not listening" >&2
                    exit 1
                fi
                """,
            )
            append('\n')
            append(installTproxyRulesCommand())
            appendScript(
                """
                ) &
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
        if (result.errno != 0) {
            AndroidAppLogger.warn(
                LogTag,
                diagnosticMessage(
                    "netstat did not report tproxy-in port $tproxyPort as listening",
                    result.stderr,
                    result.stdout,
                ),
            )
        }
        return result.errno == 0
    }

    private fun TproxyStartConfig.checkTproxyInboundCommand(): String {
        return """
            netstat -an 2>/dev/null | grep 'LISTEN' | grep "[.:]$tproxyPort[[:space:]]" >/dev/null 2>&1
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
            pid="$(cat $${pidPath.shellQuote()} 2>/dev/null || true)"
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
            pid="$(cat $${pidPath.shellQuote()} 2>/dev/null || true)"
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
