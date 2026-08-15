// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.settings.sheets

private const val MaxIgnoredInterfaceSelectorLength = 15
private const val MaxIgnoredInterfaceSelectors = 64

internal fun isIgnoredInterfaceSelector(value: String): Boolean {
    if (value.isEmpty() || value.length > MaxIgnoredInterfaceSelectorLength) return false
    val prefix = value.endsWith('+')
    val name = if (prefix) value.dropLast(1) else value
    return name.isNotEmpty() && name.all { character ->
        character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' ||
            character == '_' || character == '.' || character == '-'
    }
}

internal fun List<String>.sanitizeIgnoredInterfaceSelectors(): List<String> =
    asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .take(MaxIgnoredInterfaceSelectors)
        .toList()
