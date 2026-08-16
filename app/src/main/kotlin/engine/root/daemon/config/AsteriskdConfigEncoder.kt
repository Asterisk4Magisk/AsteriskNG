// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.daemon.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object AsteriskdConfigEncoder {
    fun encode(config: AsteriskdConfig): String {
        AsteriskdConfigValidator.validate(config)
        return AsteriskdJson.encodeToString(JsonObject.serializer(), config.toJsonObject())
            .trimEnd('\r', '\n') + "\n"
    }
}

private fun AsteriskdConfig.toJsonObject(): JsonObject = buildJsonObject {
    put("schemaVersion", 3)
    put("owner", owner.wireValue)
    put("coreType", coreType.wireValue)
    put("coreExecutablePath", coreExecutablePath)
    put("coreConfigPath", coreConfigPath)
    put("statePath", statePath)
    put("logPath", logPath)
    put("mode", mode.wireValue)
    put("core", core.toJsonObject())
    put("network", network.toJsonObject())
    put("modeOptions", modeOptions.toJsonObject())
    put("matcher", matcher?.toJsonObject() ?: JsonNull)
    put("helper", helper?.toJsonObject() ?: JsonNull)
    put("serviceControl", serviceControl.toJsonObject())
}

private fun AsteriskdServiceControlConfig.toJsonObject(): JsonObject = buildJsonObject {
    put("enabled", enabled)
    put("schedule", schedule.toJsonObject())
    put("wifi", wifi.toJsonObject())
}

private fun AsteriskdScheduleControl.toJsonObject(): JsonObject = buildJsonObject {
    put("enabled", enabled)
    put("startCron", startCron)
    put("stopCron", stopCron)
}

private fun AsteriskdWifiControl.toJsonObject(): JsonObject = buildJsonObject {
    put("enabled", enabled)
    put("connectStart", connectStart.toJsonObject())
    put("connectStop", connectStop.toJsonObject())
    put("disconnectStart", disconnectStart.toJsonObject())
    put("disconnectStop", disconnectStop.toJsonObject())
}

private fun AsteriskdWifiRule.toJsonObject(): JsonObject = buildJsonObject {
    put("enabled", enabled)
    put("ssids", ssids.toJsonArray())
    put("bssids", bssids.toJsonArray())
}

private fun AsteriskdCoreConfig.toJsonObject(): JsonObject = buildJsonObject {
    put("workingDirectory", workingDirectory)
    put("readinessTimeoutMilliseconds", readinessTimeoutMilliseconds)
    put("ageSecretKey", ageSecretKey?.let(::JsonPrimitive) ?: JsonNull)
}

private fun AsteriskdNetworkConfig.toJsonObject(): JsonObject = buildJsonObject {
    put("enableIpv6", enableIpv6)
    put("disableSystemIpv6", disableSystemIpv6)
    put("enableLocalDns", enableLocalDns)
    put("enableFakeDns", enableFakeDns)
    put("fakeDnsIpv4Pool", fakeDnsIpv4Pool?.let(::JsonPrimitive) ?: JsonNull)
    put("ignoredInterfaces", ignoredInterfaces.toJsonArray())
    put("virtualInterfaces", virtualInterfaces.toJsonArray())
    put("hotspotInterfacePrefixes", hotspotInterfacePrefixes.toJsonArray())
    put("proxyPrivateCidrs", proxyPrivateCidrs.toJsonArray())
    put("bypassPrivateCidrs", bypassPrivateCidrs.toJsonArray())
    put("appPolicy", appPolicy.toJsonObject())
}

private fun AsteriskdAppPolicy.toJsonObject(): JsonObject = buildJsonObject {
    put("mode", mode.wireValue)
    put("uids", uids.toJsonIntArray())
    put("bypassUids", bypassUids.toJsonIntArray())
    put("directCidrPathV4", directCidrPathV4?.let(::JsonPrimitive) ?: JsonNull)
    put("directCidrPathV6", directCidrPathV6?.let(::JsonPrimitive) ?: JsonNull)
}

private fun AsteriskdModeOptions.toJsonObject(): JsonObject = buildJsonObject {
    put("transparentPort", transparentPort?.let(::JsonPrimitive) ?: JsonNull)
    put("tunnelName", tunnelName?.let(::JsonPrimitive) ?: JsonNull)
}

private fun AsteriskdMatcher.toJsonObject(): JsonObject = buildJsonObject {
    put("executablePath", executablePath)
}

private fun AsteriskdHelper.toJsonObject(): JsonObject = when (this) {
    is AsteriskdHevSocks5TunnelHelper -> buildJsonObject {
        put("type", "hev-socks5-tunnel")
        put("executablePath", executablePath)
        put("socksHost", socksHost)
        put("socksPort", socksPort)
        put("tunnelName", tunnelName)
        put("mtu", mtu)
        put("ipv4Address", ipv4Address)
        put("ipv6Address", ipv6Address?.let(::JsonPrimitive) ?: JsonNull)
        put("multiQueue", multiQueue)
        put("tcpFastOpen", tcpFastOpen)
        put("tcpReadWriteTimeoutMilliseconds", tcpReadWriteTimeoutMilliseconds)
        put("udpReadWriteTimeoutMilliseconds", udpReadWriteTimeoutMilliseconds)
    }
    is AsteriskdBpf2SocksHelper -> buildJsonObject {
        put("type", "bpf2socks")
        put("executablePath", executablePath)
        put("bridgeListenAddress", bridgeListenAddress)
        put("bridgePort", bridgePort)
        put("socksHost", socksHost)
        put("socksPort", socksPort)
        put("workerCount", workerCount)
        put("tcpBufferSize", tcpBufferSize)
        put("maxTcpSessions", maxTcpSessions)
        put("tcpConnectTimeoutMilliseconds", tcpConnectTimeoutMilliseconds)
        put("tcpIdleTimeoutMilliseconds", tcpIdleTimeoutMilliseconds)
        put("udpSocketBufferSize", udpSocketBufferSize)
        put("udpBatchSize", udpBatchSize)
        put("maxUdpSessions", maxUdpSessions)
        put("maxUdpBindings", maxUdpBindings)
        put("udpIdleTimeoutSeconds", udpIdleTimeoutSeconds)
        put("maxUdpPendingBytes", maxUdpPendingBytes)
        put("dnsTransactionTimeoutMilliseconds", dnsTransactionTimeoutMilliseconds)
    }
}

private fun List<String>.toJsonArray(): JsonArray = JsonArray(map(::JsonPrimitive))
private fun List<Int>.toJsonIntArray(): JsonArray = JsonArray(map(::JsonPrimitive))

private val AsteriskdJson = Json {
    encodeDefaults = true
    explicitNulls = true
    prettyPrint = true
    prettyPrintIndent = "  "
}
