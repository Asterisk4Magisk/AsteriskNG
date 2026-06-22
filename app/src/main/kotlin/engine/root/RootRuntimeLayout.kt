// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root

import android.content.Context
import features.resources.runtime.XrayResourceFilePaths
import features.resources.runtime.prepareXrayResourceFilePaths
import java.io.File

internal data class RootRuntimeLayout(
    val configPath: String,
    val xrayCorePath: String,
    val ipv6DisablerPath: String,
    val bpfMatcherPath: String,
    val hevSocks5TunnelPath: String,
    val dataDir: String,
    val pidPath: String,
)

internal fun Context.prepareRootRuntimeLayout(): RootRuntimeLayout {
    val resourceFilePaths = prepareXrayResourceFilePaths()
    return resourceFilePaths.toRootRuntimeLayout()
}

internal fun XrayResourceFilePaths.toRootRuntimeLayout(): RootRuntimeLayout {
    val dir = File(dataDir)
    return RootRuntimeLayout(
        configPath = File(dir, RootConfigFileName).absolutePath,
        xrayCorePath = xrayCorePath,
        ipv6DisablerPath = ipv6DisablerPath,
        bpfMatcherPath = bpfMatcherPath,
        hevSocks5TunnelPath = hevSocks5TunnelPath,
        dataDir = dataDir,
        pidPath = File(dir, RootPidFileName).absolutePath,
    )
}
