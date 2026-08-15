// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package system

interface RootShellGateway {
    suspend fun exec(command: String, options: ShellExecOptions = ShellExecOptions()): ShellExecResult

    suspend fun execStreaming(
        command: String,
        options: ShellExecOptions = ShellExecOptions(),
        onStdoutLine: (String) -> Unit,
    ): ShellExecResult

    suspend fun hasRootAccess(): Boolean

}

class AndroidRootShellGateway : RootShellGateway {
    init {
        AndroidRootShell.configure()
    }

    override suspend fun exec(command: String, options: ShellExecOptions): ShellExecResult {
        return AndroidRootShell.exec(command, options)
    }

    override suspend fun execStreaming(
        command: String,
        options: ShellExecOptions,
        onStdoutLine: (String) -> Unit,
    ): ShellExecResult {
        return AndroidRootShell.execStreaming(command, options, onStdoutLine)
    }

    override suspend fun hasRootAccess(): Boolean {
        return AndroidRootShell.hasRootAccess()
    }
}
