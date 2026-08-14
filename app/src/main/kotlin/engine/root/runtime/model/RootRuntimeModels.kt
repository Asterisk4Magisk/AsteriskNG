// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime.model

enum class RootRuntimeOwner(val wireValue: String) {
    AsteriskNg("asteriskng"),
    AsteriskBox("asteriskbox"),
    AsteriskMeta("asteriskmeta"),
}

enum class RootRuntimeCoreType(val wireValue: String) {
    Xray("xray"),
    SingBox("sing-box"),
    Mihomo("mihomo"),
}

enum class RootRuntimeMode(val wireValue: String) {
    Tproxy("tproxy"),
    Tun("tun"),
    Tun2Socks("tun2socks"),
    Bpf2Socks("bpf2socks"),
    Ebpf("ebpf"),
}

enum class RootRuntimePhase(val wireValue: String) {
    Validating("validating"),
    Acquiring("acquiring"),
    Recovering("recovering"),
    Starting("starting"),
    ApplyingRules("applying-rules"),
    Running("running"),
    Stopping("stopping"),
    Stopped("stopped"),
    Failed("failed"),
}

data class RootRuntimeIdentity(
    val owner: RootRuntimeOwner,
    val coreType: RootRuntimeCoreType,
    val mode: RootRuntimeMode,
)

data class RootRuntimeSnapshot(
    val owner: RootRuntimeOwner,
    val coreType: RootRuntimeCoreType,
    val phase: RootRuntimePhase,
    val mode: RootRuntimeMode,
    val errorCode: String? = null,
)
