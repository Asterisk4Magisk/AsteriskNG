// Copyright 2026, Asterisk4Magisk contributors
// SPDX-License-Identifier: GPL-3.0
package features.enhancement

import android.content.Context
import android.os.Build
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import system.AndroidRootShellGateway
import system.RootShellGateway
import system.ShellExecOptions
import kotlin.time.Duration.Companion.milliseconds

internal class AndroidEnhancementModuleBackend(
    context: Context,
    rootFactory: () -> RootShellGateway = { AndroidRootShellGateway() },
) : EnhancementModuleBackend {
    private val appContext = context.applicationContext
    private val delegate = EnhancementModuleRootBackend(
        executable = File(appContext.applicationInfo.nativeLibraryDir, "libasteriskd.so").absolutePath,
        rootFactory = { AndroidEnhancementModuleRootAccess(rootFactory()) },
        imageReader = {
            withContext(Dispatchers.IO) {
                readEnhancementModuleImage(File(appContext.applicationInfo.sourceDir), Build.SUPPORTED_ABIS.toList())
            }
        },
        apkPath = appContext.applicationInfo.sourceDir,
    )

    override suspend fun expectedImage(): EnhancementModuleImage = delegate.expectedImage()
    override suspend fun inspect(): EnhancementModuleRuntime? = delegate.inspect()
    override suspend fun reboot() = delegate.reboot()
    override suspend fun install(onOutput: (String) -> Unit) = delegate.install(onOutput)
}

private class AndroidEnhancementModuleRootAccess(private val root: RootShellGateway) : EnhancementModuleRootAccess {
    override suspend fun requireRoot() {
        if (!root.hasRootAccess()) throw EnhancementModuleException(EnhancementModuleFailure.RootDenied)
    }

    override suspend fun execute(command: String): String = executeStreaming(command) { }

    override suspend fun executeStreaming(command: String, onOutput: (String) -> Unit): String {
        val result = try {
            // The shell-level timeout also kills the actual CLI; coroutine cancellation closes
            // the dedicated streaming shell through the existing process-lifetime adapter.
            withTimeout(135_000.milliseconds) {
                root.execStreaming(command, ShellExecOptions(logFailure = false), onOutput)
            }
        } catch (_: TimeoutCancellationException) {
            throw EnhancementModuleException(EnhancementModuleFailure.Timeout)
        }
        if (result.errno != 0) {
            val failure = if (result.errno in listOf(124, 137, 143)) EnhancementModuleFailure.Timeout else EnhancementModuleFailure.CommandFailed
            throw EnhancementModuleException(failure)
        }
        return result.stdout
    }
}
