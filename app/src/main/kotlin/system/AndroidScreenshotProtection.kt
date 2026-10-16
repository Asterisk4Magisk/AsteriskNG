// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package system

import android.app.Activity
import android.os.Build
import android.view.WindowManager

/**
 * Toggle screenshot / screen-recording protection for the current window.
 *
 * When `allowed == false`, the system blocks screenshots (recents thumbnail
 * included on Tiramisu+) and screen recording for this window.
 */
internal fun Activity.applyScreenshotProtection(allowed: Boolean) {
    if (allowed) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        setRecentsScreenshotEnabled(allowed)
    }
}
