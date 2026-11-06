package com.bkahlert.gametier.game

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal class TransferLock(private val file: Path) {
    suspend fun <T> withLock(action: suspend () -> T): T {
        Files.createDirectories(file.parent)
        return FileChannel.open(file, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            // Native Windows file-lock waits can also block cancellation.
            var lock = channel.tryLock()
            while (lock == null) {
                delay(100)
                lock = channel.tryLock()
            }
            lock.use {
                currentCoroutineContext().ensureActive()
                action()
            }
        }
    }
}
