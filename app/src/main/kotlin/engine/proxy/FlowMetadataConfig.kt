// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.proxy

import android.content.Context
import app.AppState
import app.modes.RunModeBpf2Socks
import app.modes.RunModeTun2Socks
import app.modes.RunModeVpnService
import java.io.File
import java.util.UUID
import utils.writeAtomically

internal const val FlowMetadataInboundTag = "asterisk-bridge"
internal const val FlowMetadataPort = 65530
internal const val FlowMetadataUsername = "__asterisk_flow_v1"

internal fun AppState.usesFlowMetadata(runMode: Int = this.runMode): Boolean =
    runMode == RunModeTun2Socks || runMode == RunModeBpf2Socks ||
        (runMode == RunModeVpnService && enableVpnHevTun)

/** Compiled in the app process; the service only receives the resulting config. */
@Synchronized
internal fun Context.flowMetadataOptions(): LocalProxyOptions {
    val file = File(applicationContext.filesDir, "flow-metadata-token")
    val token = if (file.isFile) {
        file.readText().trim()
    } else {
        UUID.randomUUID().toString().replace("-", "").also { value ->
            writeAtomically(file) { it.write(value.toByteArray(Charsets.US_ASCII)) }
        }
    }
    require(token.matches(Regex("[0-9a-f]{32}"))) { "Invalid bridge credential" }
    return LocalProxyOptions(
        listenAddress = LocalProxyLoopbackAddress,
        port = FlowMetadataPort,
        username = FlowMetadataUsername,
        password = token,
    )
}
