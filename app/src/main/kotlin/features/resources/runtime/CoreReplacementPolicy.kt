// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.resources.runtime

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import kotlinx.coroutines.sync.Mutex

internal enum class CoreCandidateInstallPath {
    InitialNoReplace,
    ReplaceAppOwned,
    ReplaceWithRoot,
    DeferRootOwned,
}

internal inline fun resolveCoreCandidateInstallPath(
    targetOwnerUid: () -> Int?,
    rootModeActive: () -> Boolean,
): CoreCandidateInstallPath {
    val ownerUid = targetOwnerUid() ?: return CoreCandidateInstallPath.InitialNoReplace
    if (ownerUid != RootUid) return CoreCandidateInstallPath.ReplaceAppOwned
    return if (rootModeActive()) {
        CoreCandidateInstallPath.ReplaceWithRoot
    } else {
        CoreCandidateInstallPath.DeferRootOwned
    }
}

internal class CoreReplacementCoordinator(
    private val mutex: Mutex = Mutex(),
) {
    suspend fun <Candidate> execute(
        targetOwnerUid: () -> Int?,
        rootModeActive: () -> Boolean,
        candidateFactory: () -> Candidate,
        installInitial: suspend (Candidate) -> Unit,
        replaceAppOwned: suspend (Candidate) -> Unit,
        replaceWithRoot: suspend (Candidate) -> Unit,
        deferRootOwned: suspend () -> Unit,
    ) {
        mutex.lock()
        try {
            when (resolveCoreCandidateInstallPath(targetOwnerUid, rootModeActive)) {
                CoreCandidateInstallPath.InitialNoReplace -> installInitial(candidateFactory())
                CoreCandidateInstallPath.ReplaceAppOwned -> replaceAppOwned(candidateFactory())
                CoreCandidateInstallPath.ReplaceWithRoot -> replaceWithRoot(candidateFactory())
                CoreCandidateInstallPath.DeferRootOwned -> deferRootOwned()
            }
        } finally {
            mutex.unlock()
        }
    }
}

internal val sharedCoreReplacementCoordinator = CoreReplacementCoordinator()

internal fun File.coreBinaryOwnerUidOrNull(): Int? {
    return try {
        Os.lstat(absolutePath).st_uid
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) return null
        throw IOException("Failed to inspect $absolutePath", error)
    }
}

private const val RootUid = 0
