// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.xray

import app.AppState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun XrayConfigRequest.buildXrayLogConfig(): JsonObject {
    return buildJsonObject {
        put("loglevel", appState.xrayLogLevel())
        put("access", appState.xrayAccessLogPath(coreLogPaths))
        put("error", coreLogPaths.errorLogPath)
    }
}

private fun AppState.xrayLogLevel(): String {
    return when (coreLogLevel) {
        0 -> "debug"
        1 -> "info"
        2 -> "warning"
        3 -> "error"
        4 -> XrayLogDisabled
        else -> "warning"
    }
}

private fun AppState.xrayAccessLogPath(coreLogPaths: XrayCoreLogPaths): String {
    if (!enableAccessLog) return XrayLogDisabled
    return coreLogPaths.accessLogPath.ifBlank { XrayLogDisabled }
}
