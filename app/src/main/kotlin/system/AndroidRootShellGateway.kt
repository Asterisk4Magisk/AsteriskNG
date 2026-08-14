// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package system

import kotlinx.coroutines.Deferred

interface RootShellGateway {
    suspend fun exec(command: String, options: ShellExecOptions = ShellExecOptions()): ShellExecResult

    suspend fun hasRootAccess(): Boolean

    fun launch(command: String, options: ShellExecOptions = ShellExecOptions()): Deferred<ShellExecResult>
}

class AndroidRootShellGateway : RootShellGateway {
    init {
        AndroidRootShell.configure()
    }

    override suspend fun exec(command: String, options: ShellExecOptions): ShellExecResult {
        return AndroidRootShell.exec(command, options)
    }

    override suspend fun hasRootAccess(): Boolean {
        return AndroidRootShell.hasRootAccess()
    }

    override fun launch(command: String, options: ShellExecOptions): Deferred<ShellExecResult> {
        return AndroidRootShell.launch(command, options)
    }
}
