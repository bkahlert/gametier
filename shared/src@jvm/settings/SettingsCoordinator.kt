package com.bkahlert.gametier.settings

import androidx.datastore.core.InterProcessCoordinator
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SettingsCoordinator(private val file: Path) : InterProcessCoordinator {
    private val lockFile = file.resolveSibling("${file.fileName}.lock")
    private val versionFile = file.resolveSibling("${file.fileName}.version")
    private val locks = localLocks.computeIfAbsent(file) { LocalLocks() }

    override val updateNotifications: Flow<Unit> = flow {
        Files.createDirectories(file.parent)
        file.fileSystem.newWatchService().use { watcher ->
            file.parent.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
            emit(Unit)
            while (true) {
                val key = runInterruptible { watcher.take() }
                val changed = key.pollEvents().any { it.kind() == OVERFLOW || it.context() == file.fileName }
                if (!key.reset()) throw IOException("Settings directory is no longer available: ${file.parent}")
                if (changed) emit(Unit)
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun <T> lock(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        locks.operation.withLock {
            open(lockFile).use { channel ->
                acquire(channel).use { block() }
            }
        }
    }

    override suspend fun <T> tryLock(block: suspend (Boolean) -> T): T = withContext(Dispatchers.IO) {
        if (!locks.operation.tryLock()) return@withContext block(false)
        try {
            open(lockFile).use { channel ->
                val acquired = channel.tryLock()
                if (acquired == null) block(false) else acquired.use { block(true) }
            }
        } finally { locks.operation.unlock() }
    }

    override suspend fun getVersion(): Int = version { readVersion(it) }

    override suspend fun incrementAndGetVersion(): Int = version { channel ->
        val current = readVersion(channel)
        if (current == Int.MAX_VALUE) throw IOException("Settings version limit reached: $versionFile")
        val updated = current + 1
        val bytes = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(updated).flip()
        channel.position(0)
        while (bytes.hasRemaining()) channel.write(bytes)
        channel.force(true)
        updated
    }

    private suspend fun <T> version(action: (FileChannel) -> T): T = withContext(Dispatchers.IO) {
        locks.version.withLock {
            open(versionFile).use { channel ->
                acquire(channel).use { action(channel) }
            }
        }
    }

    private fun readVersion(channel: FileChannel): Int {
        if (channel.size() == 0L) return 0
        if (channel.size() != Int.SIZE_BYTES.toLong()) throw IOException("Invalid settings version: $versionFile")
        val bytes = ByteBuffer.allocate(Int.SIZE_BYTES)
        while (bytes.hasRemaining()) {
            if (channel.read(bytes) < 0) throw IOException("Incomplete settings version: $versionFile")
        }
        return bytes.flip().int.also { if (it < 0) throw IOException("Invalid settings version: $versionFile") }
    }

    private fun open(path: Path): FileChannel {
        Files.createDirectories(path.parent)
        return FileChannel.open(path, CREATE, READ, WRITE, NOFOLLOW_LINKS)
    }

    private suspend fun acquire(channel: FileChannel): FileLock {
        // Interrupting a blocking Windows file lock can block while closing its channel.
        while (true) {
            channel.tryLock()?.let { return it }
            delay(100)
        }
    }

    private class LocalLocks(val operation: Mutex = Mutex(), val version: Mutex = Mutex())
    private companion object { val localLocks = ConcurrentHashMap<Path, LocalLocks>() }
}
