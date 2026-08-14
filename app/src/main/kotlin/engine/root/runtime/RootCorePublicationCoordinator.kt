// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import android.content.Context
import android.os.Build
import engine.root.publication.RootCoreReplacementCommand
import engine.root.publication.prepareRootPublicationDirectories
import engine.root.publication.rootRuntimeLayout
import engine.root.publication.validateElfFile
import system.RootShellGateway
import system.ShellExecOptions
import java.io.File

internal class RootCorePublicationCoordinator(
    context: Context,
    private val shell: RootShellGateway,
) {
    private val appContext = context.applicationContext
    private val layout = appContext.rootRuntimeLayout()
    private val supervisor = RootSupervisorController(appContext, shell)

    val corePath: String
        get() = layout.xrayCorePath

    fun prepareDirectories() {
        appContext.prepareRootPublicationDirectories()
    }

    fun validate(candidate: File) {
        validateElfFile(candidate.absolutePath, Build.SUPPORTED_ABIS.toList())
    }

    suspend fun isAvailable(): Boolean = supervisor.isUnbound()

    suspend fun requireAvailable() {
        supervisor.requireUnbound()
    }

    suspend fun publish(candidate: File) {
        val identity = validateElfFile(candidate.absolutePath, Build.SUPPORTED_ABIS.toList())
        val result = shell.exec(
            RootCoreReplacementCommand.build(layout, candidate.absolutePath, identity.sha256),
            ShellExecOptions(logFailure = false),
        )
        val response = result.controlResponseOrNull()
        response?.result?.snapshot?.let(supervisor::rejectBoundSnapshot)
        if (result.errno == 0 && result.stdout.isBlank()) return
        error(result.stderr.ifBlank { response?.result?.message ?: "Failed to replace Xray core" })
    }
}
