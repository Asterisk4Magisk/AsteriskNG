// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package utils

internal fun String.shellQuote(): String {
    return "'${replace("'", "'\"'\"'")}'"
}
