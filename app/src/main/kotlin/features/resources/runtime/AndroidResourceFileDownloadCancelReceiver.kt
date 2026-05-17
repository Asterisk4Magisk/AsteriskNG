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
