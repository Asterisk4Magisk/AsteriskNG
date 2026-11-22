// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import engine.root.daemon.control.AsteriskdSnapshot

internal class RootRuntimeConflictException(
    val snapshot: AsteriskdSnapshot,
) : IllegalStateException("ROOT runtime is owned by ${snapshot.owner.wireValue}")

internal class RootRuntimeBusyException(
    val snapshot: AsteriskdSnapshot,
) : IllegalStateException("AsteriskNG ROOT runtime is ${snapshot.phase.wireValue} in ${snapshot.mode.wireValue} mode")

internal class RootPrivateDnsException(reason: String) : IllegalStateException(reason)
