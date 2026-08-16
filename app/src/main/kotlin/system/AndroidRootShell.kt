// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package system

import features.logs.AndroidAppLogger
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import utils.shellQuote
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

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

    suspend fun execStreaming(
        command: String,
        options: ShellExecOptions,
        onStdoutLine: (String) -> Unit,
    ): ShellExecResult = suspendCancellableCoroutine { continuation ->
        val streamingCommand = StreamingProcessCommand.create(options.toShellCommand(command))
        val processLifetime = StreamingProcessLifetime(::terminateStreamingProcess)
        val shellReference = AtomicReference<Shell?>()
        continuation.invokeOnCancellation {
            processLifetime.cancel()
            shellReference.get()?.let { shell -> runCatching { shell.close() } }
        }
        Shell.EXECUTOR.execute {
            val callbackExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "asteriskd-watch-callback").apply { isDaemon = true }
            }
            val stdoutBacking = Collections.synchronizedList(mutableListOf<String>())
            val stderr = Collections.synchronizedList(mutableListOf<String>())
            val callbackFailure = AtomicReference<Throwable?>()
            var shell: Shell? = null
            try {
                val dedicatedShell = Shell.Builder.create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER)
                    .setTimeout(10)
                    .build()
                shell = dedicatedShell
                shellReference.set(dedicatedShell)
                if (!continuation.isActive) {
                    dedicatedShell.close()
                    return@execute
                }
                val stdout = object : CallbackList<String>(callbackExecutor, stdoutBacking) {
                    override fun onAddElement(element: String) {
                        streamingCommand.processIdOrNull(element)?.let { processId ->
                            processLifetime.publishProcessId(processId)
                            return
                        }
                        try {
                            onStdoutLine(element)
                        } catch (error: Throwable) {
                            if (callbackFailure.compareAndSet(null, error)) {
                                processLifetime.cancel()
                                shellReference.get()?.close()
                            }
                        }
                    }
                }
                val result = dedicatedShell.newJob()
                    .add(streamingCommand.shellCommand)
                    .to(stdout, stderr)
                    .exec()
                callbackExecutor.submit {}.get()
                callbackFailure.get()?.let { throw it }
                if (options.logFailure && result.code != 0) {
                    AndroidAppLogger.warn(
                        LogTag,
                        "Streaming shell command failed with code ${result.code}: $command\n" +
                            synchronized(stderr) { stderr.joinToString("\n") },
                    )
                }
                val value = ShellExecResult(
                    errno = result.code,
                    stdout = synchronized(stdoutBacking) {
                        stdoutBacking
                            .filter { line -> streamingCommand.processIdOrNull(line) == null }
                            .joinToString("\n")
                    },
                    stderr = synchronized(stderr) { stderr.joinToString("\n") },
                )
                continuation.resumeWith(Result.success(value))
            } catch (error: Throwable) {
                continuation.resumeWith(Result.failure(error))
            } finally {
                shellReference.compareAndSet(shell, null)
                runCatching { shell?.close() }
                callbackExecutor.shutdownNow()
            }
        }
    }

    suspend fun hasRootAccess(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Shell.getShell().isRoot }
            .onFailure { error -> AndroidAppLogger.warn(LogTag, "Failed to check root access", error) }
            .getOrDefault(false)
    }

    private fun terminateStreamingProcess(processId: Long) {
        Shell.EXECUTOR.execute {
            runCatching {
                Shell.cmd("kill -TERM $processId 2>/dev/null || true").exec()
            }.onFailure { error ->
                AndroidAppLogger.warn(LogTag, "Failed to terminate streaming process $processId", error)
            }
        }
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
