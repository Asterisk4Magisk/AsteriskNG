// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package ui.clipboard

internal enum class ClipboardImportMode {
    Replace,
    Merge,
}

internal enum class ClipboardImportFailure {
    EmptyClipboard,
    UnsupportedFormat,
    NoValidRoutingRules,
    NoValidApps,
    InvalidAppEntry,
    InvalidAppUserId,
    UnsupportedAppMode,
}

internal class ClipboardImportException(
    val failure: ClipboardImportFailure,
) : IllegalArgumentException(failure.name)
