// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package system

class AndroidNetworkInterfaceProvider(
    private val rootAccess: AndroidRootShellGateway,
) {
    suspend fun listNetworkInterfaces(): List<String> {
        val result = rootAccess.exec(RootNetworkInterfaceCommand, ShellExecOptions(logFailure = false))
        if (result.errno != 0) {
            error(result.stderr.ifBlank { "Unable to read Android network interfaces" })
        }
        return result.stdout
            .lineSequence()
            .toList()
            .normalizedNetworkInterfaceNames()
    }
}

private fun List<String>.normalizedNetworkInterfaceNames(): List<String> {
    return asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .filter { it != "." && it != ".." }
        .distinct()
        .toList()
}

private val RootNetworkInterfaceCommand = """
    for path in /sys/class/net/*; do
        [ -e "${'$'}path" ] || continue
        name="${'$'}{path##*/}"
        [ -n "${'$'}name" ] && echo "${'$'}name"
    done
""".trimIndent()
