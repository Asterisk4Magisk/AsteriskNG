// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.components

internal fun hasPendingStringListEdit(
    input: String,
    editingIndex: Int,
    normalize: (String) -> String = String::trim,
): Boolean = normalize(input).isNotEmpty() || editingIndex >= 0
