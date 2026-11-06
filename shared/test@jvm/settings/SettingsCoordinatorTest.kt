package com.bkahlert.gametier.settings

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test

class SettingsCoordinatorTest {
    @Test
    fun `on concurrent increments each version is distinct and persists across coordinators`() = withSettingsFile { file ->
        val first = SettingsCoordinator(file)
        val second = SettingsCoordinator(file)
        val initial = first.getVersion()

        val versions = coroutineScope {
            List(16) { index -> async(Dispatchers.IO) {
                (if (index % 2 == 0) first else second).incrementAndGetVersion()
            } }.awaitAll()
        }
        val persisted = SettingsCoordinator(file).getVersion()

        assertSoftly {
            initial shouldBe 0
            versions.sorted() shouldBe (1..16).toList()
            persisted shouldBe 16
        }
    }

    @Test
    fun `on a damaged counter access fails without resetting it`() = withSettingsFile { file ->
        val counter = Files.write(file.resolveSibling("${file.fileName}.version"), byteArrayOf(1, 2))

        val result = shouldThrow<IOException> { SettingsCoordinator(file).getVersion() }

        assertSoftly {
            result.message.isNullOrBlank() shouldBe false
            Files.readAllBytes(counter).toList() shouldBe listOf(1.toByte(), 2.toByte())
        }
    }

    @Test
    fun `on an operation holding the lock another coordinator reports contention and cancellation releases it`() = withSettingsFile { file ->
        coroutineScope {
            val entered = CompletableDeferred<Unit>()
            val operation = async(Dispatchers.IO, start = UNDISPATCHED) {
                SettingsCoordinator(file).lock { entered.complete(Unit); awaitCancellation() }
            }
            entered.await()

            val contended = SettingsCoordinator(file).tryLock { it }
            operation.cancelAndJoin()
            val released = SettingsCoordinator(file).tryLock { it }

            assertSoftly {
                contended shouldBe false
                released shouldBe true
            }
        }
    }
}
