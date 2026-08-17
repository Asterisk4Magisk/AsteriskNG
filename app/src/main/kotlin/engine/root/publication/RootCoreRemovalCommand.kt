// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.publication

import utils.shellQuote

internal object RootCoreRemovalCommand {
    fun build(corePath: String): String = "rm -f -- ${corePath.shellQuote()}"
}
