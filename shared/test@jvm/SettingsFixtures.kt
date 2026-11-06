package com.bkahlert.gametier

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

internal class MemoryPreferencesStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    private val mutex = Mutex()
    var readFailure: Throwable? = null
    var writeFailure: Throwable? = null

    override val data: Flow<Preferences> = flow {
        readFailure?.let { throw it }
        emitAll(state)
    }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
        writeFailure?.let { throw it }
        transform(state.value).also { state.value = it }
    }
}

internal fun withSettingsFile(action: suspend (Path) -> Unit) = withTestDirectory { root ->
    runBlocking { withTimeout(30.seconds) { action(root.resolve("settings.preferences_pb")) } }
}

internal suspend fun <T> withSettingsStore(file: Path, action: suspend (AppSettings) -> T): T {
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    try {
        return action(createJvmAppSettings(file, scope))
    } finally {
        withContext(NonCancellable) { scope.coroutineContext.job.cancelAndJoin() }
    }
}
