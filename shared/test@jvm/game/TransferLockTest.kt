package com.bkahlert.gametier.game

import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

class TransferLockTest {
    @Test
    fun `on cancellation the protected operation releases its file lock`() = withTestDirectory { root ->
        runBlocking {
            val file = root.resolve("locks/transfers.lock")
            val entered = CompletableDeferred<Unit>()
            val operation = async(Dispatchers.IO) {
                TransferLock(file).withLock {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            entered.await()

            withTimeout(5_000) { operation.cancelAndJoin() }
            val acquired = FileChannel.open(file, WRITE).use { it.tryLock()?.use { true } ?: false }

            assertSoftly {
                operation.isCancelled shouldBe true
                acquired shouldBe true
            }
        }
    }

    @Test
    fun `on unavailable lock storage the operation does not run`() = withTestDirectory { root ->
        runBlocking {
            val occupied = Files.writeString(root.resolve("occupied"), "retained")
            val directory = Files.createDirectory(root.resolve("directory"))
            listOf(occupied.resolve("transfers.lock"), directory).forEach { file ->
                var ran = false

                val result = shouldThrow<IOException> {
                    TransferLock(file).withLock { ran = true }
                }

                assertSoftly {
                    result.message.isNullOrBlank() shouldBe false
                    ran shouldBe false
                    Files.readString(occupied) shouldBe "retained"
                    Files.isDirectory(directory) shouldBe true
                }
            }
        }
    }
}
