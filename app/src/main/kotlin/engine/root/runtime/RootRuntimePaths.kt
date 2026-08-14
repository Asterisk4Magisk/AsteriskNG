// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import android.content.Context
import engine.root.publication.rootRuntimeLayout

internal fun Context.rootAsteriskdLogPath(): String = applicationContext.rootRuntimeLayout().asteriskdLogPath
