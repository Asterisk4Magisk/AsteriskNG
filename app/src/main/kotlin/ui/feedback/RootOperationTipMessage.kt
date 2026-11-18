// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.feedback

import android.content.Context
import app.R
import engine.root.runtime.RootOperationBlockedException
import engine.root.runtime.RootOperationResult
import engine.root.runtime.RootPrivateDnsException
import engine.root.runtime.model.RootRuntimeOwner
import features.settings.locale.localizedFromSavedLanguage

internal fun RootRuntimeOwner.productName(): String = when (this) {
    RootRuntimeOwner.AsteriskNg -> "AsteriskNG"
    RootRuntimeOwner.AsteriskMeta -> "AsteriskMETA"
    RootRuntimeOwner.AsteriskBox -> "AsteriskBOX"
}

internal fun Throwable.rootOperationTipMessageOrNull(context: Context): String? {
    if (this is RootPrivateDnsException) {
        return context.applicationContext.localizedFromSavedLanguage()
            .getString(R.string.error_app_list_enhancement_private_dns)
    }
    val result = (this as? RootOperationBlockedException)?.result
    return if (result is RootOperationResult.ForeignOwnerConflict) {
        context.getString(R.string.root_foreign_owner_conflict, result.owner.productName())
    } else {
        null
    }
}
