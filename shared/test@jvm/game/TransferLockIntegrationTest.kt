package com.bkahlert.gametier.game

import com.bkahlert.gametier.settings.DataStoreAppSettings
import com.bkahlert.gametier.settings.MemoryPreferencesStore
import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import org.junit.jupiter.api.Nested

class TransferLockIntegrationTest {
    @Nested
    inner class Processes {
        @Test
        fun `on inventory another process cannot acquire the original users transfer lock`() =
            withTestDirectory { root ->
                val result = withLockedManagerProcess(root, "inventory") { file ->
                    FileChannel.open(file, CREATE, WRITE).use { it.tryLock()?.use { false } ?: true }
                }

                result shouldBe true
            }

        @Test
        fun `on a failed transfer the lock covers the operation and is released afterwards`() =
            withTestDirectory { root ->
                var lockedDuringTransfer = false
                val file = withLockedManagerProcess(root, "promote") { file ->
                    lockedDuringTransfer = FileChannel.open(file, CREATE, WRITE).use { it.tryLock()?.use { false } ?: true }
                    file
                }
                val acquiredAfterFailure = FileChannel.open(file, WRITE).use { it.tryLock()?.use { true } ?: false }

                assertSoftly {
                    lockedDuringTransfer shouldBe true
                    acquiredAfterFailure shouldBe true
                }
            }

        @Test
        fun `on cancellation while another process owns the lock the queued operation stops`() =
            withTestDirectory { root ->
                var inspected = false
                withLockedManagerProcess(root, "inventory") { file ->
                    runBlocking {
                        val manager = PromotionManager(DataStoreAppSettings(MemoryPreferencesStore()), GameFileOperations(),
                            PromotionIndex(), BackupStore({ root.resolve("backups") }, { inspected = true; emptyList() }),
                            TransferLock(file))
                        val operation = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                            manager.inventory()
                        }

                        withTimeout(5_000) { operation.cancelAndJoin() }

                        operation.isCancelled shouldBe true
                    }
                }

                inspected shouldBe false
            }
    }
}
