// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package system

class AndroidRootShellGateway {
    init {
        AndroidRootShell.configure()
    }

    suspend fun exec(command: String, options: ShellExecOptions = ShellExecOptions()): ShellExecResult {
        return AndroidRootShell.exec(command, options)
    }

    suspend fun hasRootAccess(): Boolean {
        return AndroidRootShell.hasRootAccess()
    }
}
