// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package system

import features.logs.AndroidAppLogger
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import utils.shellQuote

internal object AndroidRootShell {
    private val configureLock = Any()

    @Volatile
    private var configured = false

    fun configure() {
        if (configured) {
            return
        }
        synchronized(configureLock) {
            if (configured) {
                return
            }
            Shell.enableVerboseLogging = false
            runCatching {
                Shell.setDefaultBuilder(
                    Shell.Builder.create()
                        .setFlags(Shell.FLAG_MOUNT_MASTER)
                        .setTimeout(10),
                )
            }.onFailure { error ->
                AndroidAppLogger.warn(LogTag, "Root shell was already initialized; keeping the existing builder", error)
            }
            configured = true
        }
    }

    suspend fun exec(command: String, options: ShellExecOptions): ShellExecResult = withContext(Dispatchers.IO) {
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()
        val result = Shell.cmd(options.toShellCommand(command))
            .to(stdout, stderr)
            .exec()
        if (options.logFailure && result.code != 0) {
            AndroidAppLogger.warn(
                LogTag,
                "Shell command failed with code ${result.code}: $command\n${stderr.joinToString("\n")}",
            )
        }
        ShellExecResult(
            errno = result.code,
            stdout = stdout.joinToString("\n"),
            stderr = stderr.joinToString("\n"),
        )
    }

    suspend fun hasRootAccess(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Shell.getShell().isRoot }
            .onFailure { error -> AndroidAppLogger.warn(LogTag, "Failed to check root access", error) }
            .getOrDefault(false)
    }

    private const val LogTag = "AndroidRootShell"
}

private fun ShellExecOptions.toShellCommand(command: String): String {
    val prefix = buildList {
        cwd?.takeIf(String::isNotBlank)?.let { add("cd ${it.shellQuote()}") }
        env.forEach { (key, value) ->
            if (key.isNotBlank()) {
                add("export $key=${value.shellQuote()}")
            }
        }
    }
    return if (prefix.isEmpty()) {
        command
    } else {
        (prefix + command).joinToString(" && ")
    }
}
