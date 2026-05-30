// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AndroidResourceFileDownloadCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ResourceFileDownloadCancelAction) {
            AndroidResourceFileDownloadCancellation.cancel()
        }
    }
}
