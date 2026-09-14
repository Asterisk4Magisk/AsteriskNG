// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.proxy.app

internal data class ScanProgressState(
    val total: Int,
    val scanned: Int,
    val matched: List<MatchedApp>,
)

internal data class MatchedApp(
    val key: String,
    val packageName: String,
    val label: String,
)
