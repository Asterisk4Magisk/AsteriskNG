// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

internal fun resolveVpnRuntimeRunning(
    runtimeRunning: Boolean,
    ownsVpnPreparation: Boolean,
): Boolean {
    return runtimeRunning && ownsVpnPreparation
}
