// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import system.getApplicationInfoCompat

internal fun Context.resolveVpnXrayProcessRules(configJson: String): String {
    val config = Json.parseToJsonElement(configJson) as? JsonObject ?: return configJson
    val routing = config["routing"] as? JsonObject ?: return configJson
    val rules = routing["rules"] as? JsonArray ?: return configJson
    val packageUids = mutableMapOf<String, String>()
    val resolvedRules = JsonArray(rules.map { element ->
        val rule = element as? JsonObject ?: return@map element
        val processes = when (val process = rule["process"]) {
            is JsonArray -> process
            is JsonPrimitive -> if (process.isString) {
                JsonArray(process.content.split(',').map(::JsonPrimitive))
            } else {
                return@map rule
            }
            else -> return@map rule
        }
        val resolvedProcesses = JsonArray(processes.map process@{ process ->
            val name = (process as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return@process process
            // The gomobile finder exposes the UID string as Xray's process name.
            // Resolve only this VPN runtime copy; ROOT rules and saved package names stay intact.
            val resolved = packageUids.getOrPut(name) {
                if (name.toIntOrNull() != null || '/' in name) {
                    name
                } else {
                    try {
                        packageManager.getApplicationInfoCompat(name).uid.takeIf { it >= 0 }?.toString() ?: name
                    } catch (_: PackageManager.NameNotFoundException) {
                        // Keep unknown names non-matching. Removing them can turn a rule into a catch-all.
                        name
                    }
                }
            }
            JsonPrimitive(resolved)
        }.distinct())
        JsonObject(rule + ("process" to resolvedProcesses))
    })
    return JsonObject(config + ("routing" to JsonObject(routing + ("rules" to resolvedRules)))).toString()
}
