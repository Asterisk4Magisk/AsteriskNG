// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package system

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

internal data class SystemUiSnapshot(
    val locale: Locale,
    val isDark: Boolean,
)

internal fun Context.currentSystemUiSnapshot(): SystemUiSnapshot {
    val configuration = applicationContext.resources.configuration
    return SystemUiSnapshot(
        locale = configuration.locales[0] ?: Locale.getDefault(),
        isDark = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES,
    )
}
