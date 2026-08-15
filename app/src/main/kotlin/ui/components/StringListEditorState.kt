// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

internal fun hasPendingStringListEdit(input: String, editingIndex: Int): Boolean =
    input.isNotBlank() || editingIndex >= 0
