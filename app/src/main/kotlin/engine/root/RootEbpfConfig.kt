// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root

import app.modes.ProxyAppListModeWhitelist
import features.resources.runtime.writeAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import utils.shellQuote
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress

internal data class RootEbpfDirectCidrs(
    val cidrs: List<String>,
    val invalidLines: List<String>,
)

internal data class RootEbpfRuntimeConfig(
    val matcherPath: String,
    val bpfPolicyPath: String,
    val directCidrPathV4: String,
    val directCidrPathV6: String,
    val directCidrSourcePathsV4: List<String>,
    val directCidrSourcePathsV6: List<String>,
    val policy: RootEbpfPolicy,
)

@Serializable
internal data class RootEbpfPolicy(
    val version: Int = RootEbpfPolicyVersion,
    val mode: Int,
    val uids: List<Int>,
    val bypassDirectCidrs: Boolean,
    val directCidrPathV4: String,
    val directCidrPathV6: String,
    val xtOutputV4ProgramPath: String,
    val xtOutputV6ProgramPath: String,
    val xtPreroutingV4ProgramPath: String,
    val xtPreroutingV6ProgramPath: String,
)

@Serializable
internal data class RootEbpfProbeResult(
    val supported: Boolean,
    val message: String = "",
    val checks: List<RootEbpfProbeCheck> = emptyList(),
)

@Serializable
internal data class RootEbpfProbeCheck(
    val name: String,
    val supported: Boolean,
    val message: String = "",
)

internal fun normalizeRootEbpfDirectCidrs(lines: Iterable<String>): RootEbpfDirectCidrs {
    val cidrs = mutableListOf<String>()
    val invalidLines = mutableListOf<String>()
    val seen = linkedSetOf<String>()
    lines.forEach { line ->
        val candidate = line.substringBefore('#').trim()
        if (candidate.isBlank()) return@forEach
        if (candidate.isValidRootEbpfCidr()) {
            if (seen.add(candidate)) {
                cidrs += candidate
            }
        } else {
            invalidLines += candidate
        }
    }
    return RootEbpfDirectCidrs(cidrs = cidrs, invalidLines = invalidLines)
}

internal fun RootEbpfPolicy.toJsonString(): String {
    return RootEbpfJson.encodeToString(
        copy(
            uids = uids.distinct().sorted(),
        ),
    )
}

internal fun RootIptablesConfig.toRootEbpfPolicy(
    directCidrPathV4: String,
    directCidrPathV6: String,
    xtOutputV4ProgramPath: String = RootEbpfXtOutputV4ProgramPath,
    xtOutputV6ProgramPath: String = RootEbpfXtOutputV6ProgramPath,
    xtPreroutingV4ProgramPath: String = RootEbpfXtPreroutingV4ProgramPath,
    xtPreroutingV6ProgramPath: String = RootEbpfXtPreroutingV6ProgramPath,
): RootEbpfPolicy {
    return RootEbpfPolicy(
        mode = proxyAppListMode,
        uids = if (proxyAppListMode == ProxyAppListModeWhitelist) {
            proxyApplicationUids + RootProxyAppWhitelistSystemUids
        } else {
            proxyApplicationUids
        },
        bypassDirectCidrs = enableEbpfDirectCidrBypass,
        directCidrPathV4 = directCidrPathV4,
        directCidrPathV6 = directCidrPathV6,
        xtOutputV4ProgramPath = xtOutputV4ProgramPath,
        xtOutputV6ProgramPath = xtOutputV6ProgramPath,
        xtPreroutingV4ProgramPath = xtPreroutingV4ProgramPath,
        xtPreroutingV6ProgramPath = xtPreroutingV6ProgramPath,
    )
}

internal fun parseRootEbpfProbeResult(value: String): RootEbpfProbeResult {
    return runCatching {
        val root = RootEbpfJson.parseToJsonElement(value).jsonObject
        RootEbpfProbeResult(
            supported = root.booleanOrDefault("supported", defaultValue = false),
            message = root.stringOrDefault("message"),
            checks = root["checks"]?.jsonArray?.mapNotNull { item ->
                val check = item.jsonObject
                val name = check.stringOrDefault("name")
                if (name.isBlank()) return@mapNotNull null
                RootEbpfProbeCheck(
                    name = name,
                    supported = check.booleanOrDefault("supported", defaultValue = false),
                    message = check.stringOrDefault("message"),
                )
            }.orEmpty(),
        )
    }.getOrDefault(
        RootEbpfProbeResult(
            supported = false,
            message = "Invalid eBPF probe response",
        ),
    )
}

internal fun RootEbpfRuntimeConfig.writeRuntimeFiles() {
    writeRootEbpfDirectCidrFile(
        path = directCidrPathV4,
        sourcePaths = directCidrSourcePathsV4,
        ipv6 = false,
    )
    writeRootEbpfDirectCidrFile(
        path = directCidrPathV6,
        sourcePaths = directCidrSourcePathsV6,
        ipv6 = true,
    )
    writeAtomically(File(bpfPolicyPath)) { output ->
        output.write(policy.toJsonString().toByteArray(Charsets.UTF_8))
    }
}

private fun writeRootEbpfDirectCidrFile(
    path: String,
    sourcePaths: List<String>,
    ipv6: Boolean,
) {
    val directCidrLines = buildList {
        sourcePaths.forEach { sourcePath ->
            val file = File(sourcePath)
            if (file.isFile) {
                addAll(file.readLines())
            }
        }
    }
    val directCidrs = normalizeRootEbpfDirectCidrs(directCidrLines).cidrs.filterRootEbpfCidrFamily(ipv6)
    writeAtomically(File(path)) { output ->
        output.write((directCidrs.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
    }
}

internal fun RootEbpfRuntimeConfig.buildStartCommand(): String {
    val startCommand = listOf(
        matcherPath.shellQuote(),
        "--start",
        "--policy",
        bpfPolicyPath.shellQuote(),
    ).joinToString(" ")
    return buildString {
        appendScript(
            $$"""
            chmod 755 $${matcherPath.shellQuote()}
            $$startCommand
            """,
        )
    }
}

internal fun RootRuntimeLayout.buildStopRootEbpfCommand(): String {
    return buildString {
        appendScript(
            $$"""
            if [ -x $${bpfMatcherPath.shellQuote()} ]; then
                $${bpfMatcherPath.shellQuote()} --stop --policy $${bpfPolicyPath.shellQuote()} >/dev/null 2>&1 || true
            fi
            rm -f $${RootEbpfXtOutputV4ProgramPath.shellQuote()} 2>/dev/null || true
            rm -f $${RootEbpfXtOutputV6ProgramPath.shellQuote()} 2>/dev/null || true
            rm -f $${RootEbpfXtPreroutingV4ProgramPath.shellQuote()} 2>/dev/null || true
            rm -f $${RootEbpfXtPreroutingV6ProgramPath.shellQuote()} 2>/dev/null || true
            rm -f $${RootEbpfProbeXtOutputV4ProgramPath.shellQuote()} 2>/dev/null || true
            rm -f $${RootEbpfProbeXtOutputV6ProgramPath.shellQuote()} 2>/dev/null || true
            rm -f $${RootEbpfProbeXtPreroutingV4ProgramPath.shellQuote()} 2>/dev/null || true
            rm -f $${RootEbpfProbeXtPreroutingV6ProgramPath.shellQuote()} 2>/dev/null || true
            rmdir $${RootEbpfPinnedObjectDir.shellQuote()} 2>/dev/null || true
            """,
        )
    }
}

private fun String.isValidRootEbpfCidr(): Boolean {
    val parts = split('/')
    if (parts.size != 2) return false
    val address = parts[0].trim()
    val prefix = parts[1].toIntOrNull() ?: return false
    return when {
        "." in address && ":" !in address -> address.isValidIpv4Address() && prefix in 0..32
        ":" in address -> address.isValidIpv6Address() && prefix in 0..128
        else -> false
    }
}

private fun String.isValidIpv4Address(): Boolean {
    val octets = split('.')
    if (octets.size != 4) return false
    return octets.all { octet ->
        octet.isNotBlank() &&
            octet.all(Char::isDigit) &&
            octet.toIntOrNull()?.let { value -> value in 0..255 } == true
    }
}

private fun String.isValidIpv6Address(): Boolean {
    return runCatching {
        InetAddress.getByName(this) is Inet6Address
    }.getOrDefault(false)
}

private fun List<String>.filterRootEbpfCidrFamily(ipv6: Boolean): List<String> {
    return filter { cidr -> (":" in cidr) == ipv6 }
}

private fun JsonObject.stringOrDefault(key: String, defaultValue: String = ""): String {
    return this[key]?.jsonPrimitive?.content ?: defaultValue
}

private fun JsonObject.booleanOrDefault(key: String, defaultValue: Boolean): Boolean {
    return this[key]?.jsonPrimitive?.booleanOrNull ?: defaultValue
}

private const val RootEbpfPolicyVersion = 1

private val RootEbpfJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
