// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.quicksettings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Runtime events the ROOT supervisor sends by explicit component; the intent carries no state. */
class QuickSettingsTileRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        requestQuickSettingsTileRefresh(context)
    }
}
