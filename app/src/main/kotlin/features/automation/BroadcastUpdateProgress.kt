// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.automation

import android.annotation.SuppressLint
import android.content.Context

/** A system-stopped Worker keeps its ID; a new user request gets a fresh checkpoint. */
internal class BroadcastUpdateProgress(context: Context, kind: String, workId: String) {
    private val preferences = context.getSharedPreferences("broadcast_update_progress", Context.MODE_PRIVATE)
    // Different generations must never overwrite one another during cancel/restart races.
    private val key = "$kind.$workId"

    fun completed(item: String): Boolean = synchronized(Lock) {
        item in preferences.getStringSet("$key.items", emptySet()).orEmpty()
    }

    // KTX edit discards commit()'s result; a failed checkpoint must be reported.
    @SuppressLint("UseKtx")
    fun record(item: String, success: Boolean) = synchronized(Lock) {
        val items = preferences.getStringSet("$key.items", emptySet()).orEmpty()
        check(preferences.edit()
            .putStringSet("$key.items", items + item)
            .putBoolean("$key.failed", !success || preferences.getBoolean("$key.failed", false))
            .commit()) { "Failed to save broadcast update progress" }
    }

    fun succeeded(): Boolean = synchronized(Lock) {
        !preferences.getBoolean("$key.failed", false)
    }

    // KTX edit discards commit()'s result; a failed cleanup must be reported.
    @SuppressLint("UseKtx")
    fun clear() = synchronized(Lock) {
        check(preferences.edit().remove("$key.items").remove("$key.failed").commit())
    }

    private companion object { val Lock = Any() }
}
