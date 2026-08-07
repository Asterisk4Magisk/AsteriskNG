// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn.hevtun

import engine.hevtun.HevSocks5TunnelConfig
import engine.hevtun.writeConfigFile

internal class HevTunRuntime(
    private val nativeGateway: HevTunNativeGateway = HevTunNative,
) {
    private var running = false

    fun start(config: HevSocks5TunnelConfig, tunFd: Int) {
        stop()
        config.writeConfigFile()
        check(nativeGateway.startService(config.configPath, tunFd)) {
            "Failed to start Hev TUN native service"
        }
        running = true
    }

    fun stop() {
        if (!running) return
        try {
            check(nativeGateway.stopService()) {
                "Failed to stop Hev TUN native service"
            }
        } finally {
            running = false
        }
    }
}
