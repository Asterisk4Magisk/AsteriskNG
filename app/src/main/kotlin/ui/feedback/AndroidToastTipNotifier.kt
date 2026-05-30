// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.feedback

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidToastTipNotifier(context: Context) {
    private val appContext = context.applicationContext

    suspend fun show(message: String) {
        withContext(Dispatchers.Main.immediate) {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun showError(error: Throwable, fallbackMessage: String? = null) {
        val message = error.tipMessage(fallbackMessage)
        show(message)
    }
}

private fun Throwable.tipMessage(fallbackMessage: String? = null): String {
    return message.orEmpty().ifBlank {
        fallbackMessage.orEmpty().ifBlank { this::class.simpleName.orEmpty() }
    }
}
