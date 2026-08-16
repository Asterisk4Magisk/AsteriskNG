// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.publication

internal data class RootPublicationBundle(
    val runtimeLayout: RootRuntimeLayout,
    val coreConfigSourcePath: String,
    val asteriskdConfigSourcePath: String,
    val bootEnabled: Boolean,
    val launchMode: RootPublicationLaunchMode = RootPublicationLaunchMode.Service,
    val restartExpectedOwner: String? = null,
) {
    init {
        require(restartExpectedOwner == null || restartExpectedOwner in RootPublicationOwners)
    }
}

internal enum class RootPublicationLaunchMode {
    None,
    Service,
    Monitor,
}

private val RootPublicationOwners = setOf("asteriskng", "asteriskbox", "asteriskmeta")
