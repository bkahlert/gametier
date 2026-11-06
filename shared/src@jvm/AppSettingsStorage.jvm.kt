package com.bkahlert.gametier

import androidx.datastore.core.FileStorage
import androidx.datastore.core.Serializer
import androidx.datastore.core.Storage
import androidx.datastore.core.StorageConnection
import androidx.datastore.core.WriteScope
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun appSettingsStorage(file: Path): Storage<Preferences> = object : Storage<Preferences> {
    override fun createConnection(): StorageConnection<Preferences> {
        val canonicalFile = file.toFile().canonicalFile
        val serializer = object : Serializer<Preferences> by PreferencesFileSerializer {
            override val defaultValue: Preferences
                get() = missingSettings(canonicalFile.toPath())
        }
        val connection = FileStorage(serializer) { canonicalFile }.createConnection()
        return object : StorageConnection<Preferences> by connection {
            private val writeMutex = Mutex()

            // DataStore 1.3 updates its cache before FileStorage.writeScope commits.
            // Commit its single write before writeData returns, so rename failures
            // cannot publish an uncommitted preference to observers.
            override suspend fun writeScope(block: suspend WriteScope<Preferences>.() -> Unit) = writeMutex.withLock {
                val scope = object : WriteScope<Preferences> {
                    private var closed = false

                    override suspend fun readData(): Preferences {
                        check(!closed) { "Settings write scope is closed" }
                        return connection.readScope { readData() }
                    }

                    override suspend fun writeData(value: Preferences) {
                        check(!closed) { "Settings write scope is closed" }
                        connection.writeScope { writeData(value) }
                    }

                    override fun close() {
                        closed = true
                    }
                }
                try {
                    block(scope)
                } finally {
                    scope.close()
                }
            }
        }
    }
}

private fun missingSettings(file: Path): Preferences {
    var ancestor = file
    while (true) {
        try {
            val attributes = Files.readAttributes(ancestor, BasicFileAttributes::class.java)
            if (ancestor == file) throw IOException("Cannot read the existing settings file: $file")
            if (!attributes.isDirectory) throw NotDirectoryException(ancestor.toString())
            return PreferencesFileSerializer.defaultValue
        } catch (missing: NoSuchFileException) {
            ancestor = ancestor.parent ?: throw missing
        }
    }
}
