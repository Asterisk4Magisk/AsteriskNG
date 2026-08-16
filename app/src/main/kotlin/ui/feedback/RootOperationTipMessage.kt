// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.feedback

import engine.root.runtime.RootOperationBlockedException
import engine.root.runtime.RootOperationResult
import engine.root.runtime.model.RootRuntimeOwner

internal fun RootRuntimeOwner.productName(): String = when (this) {
    RootRuntimeOwner.AsteriskNg -> "AsteriskNG"
    RootRuntimeOwner.AsteriskMeta -> "AsteriskMETA"
    RootRuntimeOwner.AsteriskBox -> "AsteriskBOX"
}

internal fun Throwable.rootOperationTipMessageOrNull(
    formatForeignOwnerConflict: (String) -> String,
): String? {
    val result = (this as? RootOperationBlockedException)?.result
    return if (result is RootOperationResult.ForeignOwnerConflict) {
        formatForeignOwnerConflict(result.owner.productName())
    } else {
        null
    }
}
