// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime.model

enum class RootRuntimeOwner(val wireValue: String) {
    AsteriskNg("asteriskng"),
    AsteriskBox("asteriskbox"),
    AsteriskMeta("asteriskmeta"),
}

enum class RootRuntimeMode(val wireValue: String) {
    Tproxy("tproxy"),
    Tun("tun"),
    Tun2Socks("tun2socks"),
    Bpf2Socks("bpf2socks"),
    Ebpf("ebpf"),
}

data class RootRuntimeSnapshot(
    val owner: RootRuntimeOwner,
    val mode: RootRuntimeMode,
    val running: Boolean,
)
