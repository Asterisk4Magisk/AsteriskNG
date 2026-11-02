// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package features.logs

import android.os.Build
import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * Follows changes of log files through inotify, so nothing reads a log until it changes.
 *
 * Every directory gets a single watch shared by all its listeners: before Android 10 a second
 * [FileObserver] on the same path silently replaced the first one. The watch asks the kernel
 * only for the events its listeners need, so a busy file nobody follows wakes nothing.
 */
internal object LogDirectoryWatcher {
    private class Listener(val fileName: String, val mask: Int, val onChange: () -> Unit)

    private class Watch(val directory: File) {
        val listeners = mutableListOf<Listener>()
        var observer: FileObserver? = null
        var mask = 0

        /** Recreates the observer when the events the listeners need have changed. */
        fun update() {
            val wanted = synchronized(listeners) { listeners.fold(0) { acc, listener -> acc or listener.mask } }
            if (wanted == mask) return
            observer?.stopWatching()
            mask = wanted
            observer = if (wanted == 0) {
                null
            } else {
                createFileObserver(directory, wanted) { event, path -> dispatch(listeners, event, path) }
                    .also(FileObserver::startWatching)
            }
        }
    }

    private val watches = mutableMapOf<String, Watch>()

    /**
     * Calls [onChange] after inotify reports any of [mask] for [file]. The watch is on its
     * directory, so it survives the file being deleted and created again. Returns the call that
     * removes the listener again.
     */
    fun watch(file: File, mask: Int, onChange: () -> Unit): () -> Unit {
        val directory = checkNotNull(file.absoluteFile.parentFile) { "Log file has no directory: $file" }
        directory.mkdirs()
        val listener = Listener(file.name, mask, onChange)
        val key = directory.absolutePath
        synchronized(watches) {
            val watch = watches.getOrPut(key) { Watch(directory) }
            synchronized(watch.listeners) { watch.listeners += listener }
            watch.update()
        }
        return {
            synchronized(watches) {
                val watch = watches[key] ?: return@synchronized
                synchronized(watch.listeners) { watch.listeners -= listener }
                watch.update()
                if (watch.observer == null) watches -= key
            }
        }
    }

    /**
     * Like [watch], but runs [onChange] in [scope] and folds a burst of changes into one call,
     * at most one per [minIntervalMillis], so a chatty writer cannot cause a read per write.
     */
    fun watchCoalesced(
        file: File,
        mask: Int,
        scope: CoroutineScope,
        minIntervalMillis: Long,
        onChange: suspend () -> Unit,
    ): () -> Unit {
        val changes = Channel<Unit>(Channel.CONFLATED)
        scope.launch {
            for (change in changes) {
                onChange()
                delay(minIntervalMillis.milliseconds)
            }
        }
        val unwatch = watch(file, mask) { changes.trySend(Unit) }
        return {
            unwatch()
            changes.close()
        }
    }

    private fun dispatch(listeners: List<Listener>, event: Int, path: String?) {
        if (path == null) return
        val matching = synchronized(listeners) {
            listeners.filter { listener -> listener.fileName == path && event and listener.mask != 0 }
        }
        matching.forEach { listener -> listener.onChange() }
    }

    private fun createFileObserver(directory: File, mask: Int, onEvent: (Int, String?) -> Unit): FileObserver {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(directory, mask) {
                override fun onEvent(event: Int, path: String?) = onEvent(event, path)
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(directory.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) = onEvent(event, path)
            }
        }
    }
}
