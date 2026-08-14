// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.root.runtime

import engine.root.daemon.control.AsteriskdFailureCode
import engine.root.daemon.control.AsteriskdPhase
import engine.root.daemon.control.AsteriskdSnapshot

internal class RootRuntimeConflictException(
    val snapshot: AsteriskdSnapshot,
) : IllegalStateException("ROOT runtime is owned by ${snapshot.owner.wireValue}")

internal class RootRuntimeBusyException(
    val snapshot: AsteriskdSnapshot,
) : IllegalStateException("AsteriskNG ROOT runtime is ${snapshot.phase.wireValue} in ${snapshot.mode.wireValue} mode")

internal class RootProtocolException(
    operation: String,
    cause: Throwable,
) : IllegalStateException("Invalid asteriskd response for $operation", cause)

internal class RootPublicationException(
    stage: String,
    cause: Throwable,
) : IllegalStateException("ROOT publication failed during $stage", cause)

internal class RootStartFailedException(
    val code: AsteriskdFailureCode?,
    val phase: AsteriskdPhase?,
    cause: Throwable,
) : IllegalStateException("ROOT supervisor failed to start", cause)
