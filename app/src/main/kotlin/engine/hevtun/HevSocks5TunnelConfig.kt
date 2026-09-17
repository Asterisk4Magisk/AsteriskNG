// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.hevtun

import engine.network.NetworkDefaults
import engine.proxy.LocalProxyLoopbackAddress
import engine.proxy.LocalProxyOptions
import engine.xray.XrayCoreLogPaths
import engine.xray.logDirectoryPath
import utils.writeAtomically
import java.io.File

internal data class HevSocks5TunnelConfig(
    val executablePath: String = "",
    val configPath: String,
    val pidPath: String = "",
    val logPath: String,
    val socksAddress: String,
    val socksPort: Int,
    val socksUsername: String = "",
    val socksPassword: String = "",
    val mtu: Int,
    val ipv4Address: String,
    val ipv6Address: String?,
    val tunnelName: String? = null,
    val enableMultiQueue: Boolean = false,
    val enableTcpFastOpen: Boolean = false,
    val tcpReadWriteTimeoutMillis: Int = DefaultHevSocks5TunnelTcpReadWriteTimeoutMillis,
    val udpReadWriteTimeoutMillis: Int = DefaultHevSocks5TunnelUdpReadWriteTimeoutMillis,
    val logLevel: String = DefaultHevSocks5TunnelLogLevel,
)

internal fun HevSocks5TunnelConfig.writeConfigFile() {
    writeAtomically(File(configPath)) { output ->
        output.write(buildHevSocks5TunnelConfigYaml().toByteArray(Charsets.UTF_8))
    }
}

private fun HevSocks5TunnelConfig.buildHevSocks5TunnelConfigYaml(): String {
    return buildString {
        appendLine("tunnel:")
        tunnelName?.let { name ->
            appendLine("  name: ${name.toYamlSingleQuotedString()}")
        }
        appendLine("  mtu: $mtu")
        if (enableMultiQueue) {
            appendLine("  multi-queue: true")
        }
        appendLine("  ipv4: ${ipv4Address.toYamlSingleQuotedString()}")
        ipv6Address?.let { address ->
            appendLine("  ipv6: ${address.toYamlSingleQuotedString()}")
        }
        appendLine("socks5:")
        appendLine("  port: $socksPort")
        appendLine("  address: ${socksAddress.toYamlSingleQuotedString()}")
        appendLine("  udp: 'udp'")
        if (socksUsername.isNotBlank()) {
            appendLine("  username: ${socksUsername.toYamlSingleQuotedString()}")
            appendLine("  password: ${socksPassword.toYamlSingleQuotedString()}")
        }
        if (enableTcpFastOpen) {
            appendLine("  tcp-fastopen: true")
        }
        appendLine("misc:")
        appendLine("  tcp-read-write-timeout: $tcpReadWriteTimeoutMillis")
        appendLine("  udp-read-write-timeout: $udpReadWriteTimeoutMillis")
        appendLine("  log-file: ${logPath.toYamlSingleQuotedString()}")
        appendLine("  log-level: $logLevel")
    }
}

internal fun hevSocks5TunnelSocksTargetAddress(localProxyOptions: LocalProxyOptions): String {
    return if (localProxyOptions.listenAddress == NetworkDefaults.IPV4_ANY_ADDRESS) {
        LocalProxyLoopbackAddress
    } else {
        localProxyOptions.listenAddress
    }
}

internal fun XrayCoreLogPaths.hevSocks5TunnelLogFile(fileName: String): File {
    return File(logDirectoryPath(), fileName)
}

private fun String.toYamlSingleQuotedString(): String {
    return "'${replace("'", "''")}'"
}
