// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.publication

import java.io.File

internal object RootBootConfigWriter {
    fun write(
        layout: RootRuntimeLayout,
        coreConfigBytes: ByteArray,
        encodedDaemonConfig: String,
    ) {
        File(layout.configPath).writeBytes(coreConfigBytes)
        File(layout.asteriskdConfigPath).writeBytes(encodedDaemonConfig.toByteArray(Charsets.UTF_8))
    }
}
