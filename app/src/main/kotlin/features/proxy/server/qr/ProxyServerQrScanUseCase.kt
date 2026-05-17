// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.server.qr

class ProxyServerQrScanUseCase(
    private val scanQrCode: suspend () -> String?,
) {
    suspend fun scan(): String? {
        return scanQrCode()
    }
}
