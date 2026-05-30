// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package data

import android.content.Context
import androidx.room.Room
import app.AppState
import features.logs.AndroidAppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AndroidAppStateStore private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private var database = buildDatabase()
    private var dao = database.appStateDao()
    private val settingsPreferences = AppSettingsPreferences(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updateLock = Any()
    private val saveMutex = Mutex()
    private val saveRevision = AtomicLong(0)
    private val hasPersistedState = AtomicBoolean(false)
    private val loadedState = loadInitialState()
    private val mutableState = MutableStateFlow(loadedState.state)

    init {
        hasPersistedState.set(loadedState.loadedFromDatabase)
    }

    val state: StateFlow<AppState> = mutableState.asStateFlow()

    fun update(transform: (AppState) -> AppState) {
        val pendingSave = synchronized(updateLock) {
            val previousState = mutableState.value
            val nextState = transform(previousState)
            if (nextState == previousState) {
                null
            } else {
                mutableState.value = nextState
                PendingStateSave(
                    previousState = previousState,
                    nextState = nextState,
                    revision = saveRevision.incrementAndGet(),
                )
            }
        } ?: return

        persist(pendingSave.previousState, pendingSave.nextState, pendingSave.revision)
    }

    private fun loadInitialState(): LoadedAppState {
        return runBlocking(Dispatchers.IO) {
            val persistedState = runCatching {
                dao.loadState()
            }.onFailure { error ->
                AndroidAppLogger.error(LogTag, "Failed to load app state", error)
                resetDatabase()
            }.getOrNull()
            val settingsState = settingsPreferences.load()
            if (persistedState?.hasRoomContent() == true) {
                LoadedAppState(
                    state = persistedState.toAppState(settingsState),
                    loadedFromDatabase = true,
                )
            } else {
                LoadedAppState(
                    state = settingsState,
                    loadedFromDatabase = false,
                )
            }
        }
    }

    private fun persist(previousState: AppState, nextState: AppState, revision: Long) {
        scope.launch {
            saveMutex.withLock {
                if (revision != saveRevision.get()) {
                    return@withLock
                }
                runCatching {
                    settingsPreferences.save(nextState)
                    dao.saveState(
                        previousState = previousState,
                        nextState = nextState,
                        replaceAll = !hasPersistedState.get(),
                    )
                    hasPersistedState.set(true)
                }.onFailure { error ->
                    AndroidAppLogger.error(LogTag, "Failed to persist app state", error)
                    resetDatabase()
                    runCatching {
                        settingsPreferences.save(nextState)
                        dao.saveState(
                            previousState = AppState(),
                            nextState = nextState,
                            replaceAll = true,
                        )
                        hasPersistedState.set(true)
                    }.onFailure { retryError ->
                        AndroidAppLogger.error(LogTag, "Failed to persist app state after database reset", retryError)
                    }
                }
            }
        }
    }

    private fun buildDatabase(): AsteriskAppDatabase {
        return Room.databaseBuilder(
            appContext,
            AsteriskAppDatabase::class.java,
            AsteriskDatabaseName,
        )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    private fun resetDatabase() {
        runCatching { database.close() }
            .onFailure { error -> AndroidAppLogger.warn(LogTag, "Failed to close app state database before reset", error) }
        runCatching { appContext.deleteDatabase(AsteriskDatabaseName) }
            .onFailure { error -> AndroidAppLogger.warn(LogTag, "Failed to delete app state database during reset", error) }
        database = buildDatabase()
        dao = database.appStateDao()
        hasPersistedState.set(false)
    }

    companion object {
        private const val LogTag = "AndroidAppStateStore"

        @Volatile
        private var instance: AndroidAppStateStore? = null

        fun get(context: Context): AndroidAppStateStore {
            return instance ?: synchronized(this) {
                instance ?: AndroidAppStateStore(context).also { store ->
                    instance = store
                }
            }
        }
    }
}

private data class PendingStateSave(
    val previousState: AppState,
    val nextState: AppState,
    val revision: Long,
)

private data class LoadedAppState(
    val state: AppState,
    val loadedFromDatabase: Boolean,
)
