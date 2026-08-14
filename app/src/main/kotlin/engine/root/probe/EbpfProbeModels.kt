// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.probe

import kotlinx.serialization.Serializable

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
