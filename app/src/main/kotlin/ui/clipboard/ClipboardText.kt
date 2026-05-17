// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.clipboard

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard

const val PlainTextLabel = "plain text"

suspend fun Clipboard.getPlainText(): String? {
    val clipData = getClipEntry()?.clipData ?: return null
    if (clipData.itemCount <= 0) {
        return null
    }
    return clipData.getItemAt(0)?.text?.toString()
}

suspend fun Clipboard.setPlainText(text: String) {
    setClipEntry(ClipEntry(ClipData.newPlainText(PlainTextLabel, text)))
}
