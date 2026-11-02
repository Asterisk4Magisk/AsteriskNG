// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package engine.vpn

import android.content.Context
import engine.proxy.LocalProxyOptions
import features.logs.AndroidAppLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/**
 * The cross-process record of a running VPN tunnel.
 *
 * The service holds an exclusive file lock on [FileName] for exactly as long as the tunnel is
 * up, and the file carries the local proxy the tunnel serves. The kernel drops the lock when the
 * service process dies, so a crash can never leave a running session behind; a file that is
 * present but unlocked marks a session that ended without an orderly stop.
 */
internal object VpnSession {
    private const val FileName = "vpn-session"
    private const val LogTag = "VpnSession"
    private const val KeyListenAddress = "listen_address"
    private const val KeyPort = "port"
    private const val KeyUsername = "username"
    private const val KeyPassword = "password"

    private val probeLock = Any()

    /**
     * Whether this process holds the session. The lock cannot be probed from its owner: an
     * overlapping lock in the same JVM throws, and closing a second descriptor of the file would
     * drop every POSIX lock this process holds. The owner therefore answers from here.
     */
    @Volatile
    private var ownedHere = false

    /** Whether a thread of this process waits on the session lock; written under [probeLock]. */
    @Volatile
    private var watching = false

    private val mutableEnded = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Publishes a session from the service process. Closing the result removes the file before
     * the lock is released, so an orderly stop never reads back as an interrupted session.
     */
    fun open(context: Context, localProxyOptions: LocalProxyOptions): Closeable {
        val file = file(context)
        file.parentFile?.mkdirs()
        // A session that ended without an orderly stop leaves its file behind. Start from a new
        // file so a watcher still holding the old one can never delay this lock.
        file.delete()
        val access = RandomAccessFile(file, "rw")
        try {
            val lock = access.channel.lock()
            access.setLength(0L)
            access.write(localProxyOptions.toSessionJson().toString().toByteArray(Charsets.UTF_8))
            ownedHere = true
            return Closeable {
                runCatching { file.delete() }
                runCatching { lock.release() }
                runCatching { access.close() }
                ownedHere = false
            }
        } catch (error: Throwable) {
            runCatching { access.close() }
            throw error
        }
    }

    /**
     * Whether the service process currently holds the session. Probes from other processes are
     * serialized because two overlapping probes in one process would throw.
     */
    fun isRunning(context: Context): Boolean {
        if (ownedHere) return true
        val file = file(context)
        if (!file.isFile) return false
        return synchronized(probeLock) {
            // A parked watcher proves the lock is held, and probing beside it would overlap.
            if (watching) return@synchronized true
            runCatching {
                RandomAccessFile(file, "r").use { access ->
                    val probe = access.channel.tryLock(0L, Long.MAX_VALUE, true) ?: return@use true
                    probe.release()
                    false
                }
            }.getOrElse { error ->
                AndroidAppLogger.warn(LogTag, "Failed to probe the VPN session", error)
                false
            }
        }
    }

    /**
     * Arms a watch on the running session from another process: one thread waits on a shared
     * lock of the session file, which the kernel grants once the service releases its lock,
     * whether through an orderly stop or because its process died. [ended] then fires. Nothing
     * is polled, and a watch that is already armed, or a session that is not running, makes
     * this a no-op.
     */
    fun watch(context: Context) {
        if (ownedHere) return
        val file = file(context)
        val access = synchronized(probeLock) {
            if (watching || !file.isFile) return
            val access = runCatching { RandomAccessFile(file, "r") }.getOrElse { return }
            val probe = runCatching { access.channel.tryLock(0L, Long.MAX_VALUE, true) }.getOrElse { error ->
                AndroidAppLogger.warn(LogTag, "Failed to probe the VPN session", error)
                null
            }
            if (probe != null) {
                // Nobody holds the session, so there is nothing to wait for.
                probe.release()
                runCatching { access.close() }
                return
            }
            watching = true
            access
        }
        thread(name = "$LogTag-watch", isDaemon = true) {
            val released = runCatching {
                access.channel.lock(0L, Long.MAX_VALUE, true).release()
            }.onFailure { error ->
                AndroidAppLogger.warn(LogTag, "Failed to watch the VPN session", error)
            }.isSuccess
            synchronized(probeLock) {
                runCatching { access.close() }
                watching = false
            }
            // A failed wait reports nothing; the next status probe arms a new watch.
            if (released) mutableEnded.tryEmit(Unit)
        }
    }

    /** Fires in a watching process each time a session it watched ends. */
    val ended: SharedFlow<Unit> = mutableEnded.asSharedFlow()

    /** A session that ended without an orderly stop, for example when an app update killed it. */
    fun wasInterrupted(context: Context): Boolean {
        return file(context).isFile && !isRunning(context)
    }

    /** The local proxy of the session while it runs, or null when none runs or it serves none. */
    fun runningLocalProxyOptions(context: Context): LocalProxyOptions? {
        return if (isRunning(context)) localProxyOptions(context) else null
    }

    /** The local proxy of the running session, or null when the tunnel serves none. */
    fun localProxyOptions(context: Context): LocalProxyOptions? {
        val record = readRecord(context) ?: return null
        return LocalProxyOptions(
            listenAddress = record.optString(KeyListenAddress),
            port = record.optInt(KeyPort, 0),
            username = record.optString(KeyUsername),
            password = record.optString(KeyPassword),
        ).takeIf { options -> options.port > 0 }
    }

    private fun readRecord(context: Context): JSONObject? {
        val file = file(context)
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    private fun LocalProxyOptions.toSessionJson(): JSONObject {
        if (port <= 0) return JSONObject()
        return JSONObject()
            .put(KeyListenAddress, listenAddress)
            .put(KeyPort, port)
            .put(KeyUsername, username)
            .put(KeyPassword, password)
    }

    private fun file(context: Context): File = File(context.filesDir, FileName)
}
