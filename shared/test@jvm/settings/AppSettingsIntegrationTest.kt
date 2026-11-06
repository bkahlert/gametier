package com.bkahlert.gametier.settings

import com.bkahlert.gametier.testsupport.filesystem.testVolumeCases

import androidx.datastore.core.CorruptionException
import com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE
import com.sun.nio.file.ExtendedOpenOption.NOSHARE_READ
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import kotlin.test.Test
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.TestFactory

class AppSettingsIntegrationTest {
    @Nested
    inner class Persistence {
        @Test
        fun `on missing storage and parents the preference starts unset`() = withSettingsFile { file ->
            val nested = file.parent.resolve("LocalAppData/GameTier/settings.preferences_pb")

            val result = withSettingsStore(nested) { it.defaultPromotionFolder.first() }

            result shouldBe null
        }

        @Test
        fun `on reopening the committed folder is retained`() = withSettingsFile { file ->
            val folder = file.parent.resolve("missing/../Games").toString() + "/"
            withSettingsStore(file) { settings ->
                val saved = settings.setDefaultPromotionFolder(folder)
                val result = settings.defaultPromotionFolder.first()

                assertSoftly {
                    saved shouldBe Unit
                    result shouldBe folder
                }
            }

            val result = withSettingsStore(file) { it.defaultPromotionFolder.first() }

            result shouldBe folder
        }

        @Test
        fun `on reopening after clearing the folder remains unset`() = withSettingsFile { file ->
            withSettingsStore(file) { settings ->
                settings.setDefaultPromotionFolder(file.parent.resolve("Games").toString())
                val result = settings.clearDefaultPromotionFolder()

                result shouldBe Unit
            }

            val result = withSettingsStore(file) { it.defaultPromotionFolder.first() }

            result shouldBe null
        }

        @Test
        fun `on first use missing storage parents are created`() = withSettingsFile { file ->
            val nested = file.parent.resolve("LocalAppData/GameTier/settings.preferences_pb")
            val folder = file.parent.resolve("Games").toString()

            val saved = withSettingsStore(nested) { it.setDefaultPromotionFolder(folder) }
            val result = withSettingsStore(nested) { it.defaultPromotionFolder.first() }

            assertSoftly {
                saved shouldBe Unit
                Files.isRegularFile(nested) shouldBe true
                result shouldBe folder
                Files.exists(Path.of(folder)) shouldBe false
            }
        }

        @Test
        fun `on a successful save an existing observer receives the persisted folder`() = withSettingsFile { file ->
            withSettingsStore(file) { settings ->
                settings.defaultPromotionFolder.first() shouldBe null
                val folder = file.parent.resolve("Games").toString()
                coroutineScope {
                    val observation = async(start = UNDISPATCHED) {
                        settings.defaultPromotionFolder.first { it == folder }
                    }

                    val saved = settings.setDefaultPromotionFolder(folder)
                    val result = observation.await()

                    assertSoftly {
                        saved shouldBe Unit
                        result shouldBe folder
                    }
                }
            }
        }

        @Test
        fun `on concurrent saves the final committed folder survives reopening`() = withSettingsFile { file ->
            val folders = List(16) { file.parent.resolve("Games-$it").toString() }
            val committed = withSettingsStore(file) { settings ->
                val results = coroutineScope {
                    folders.map { folder ->
                        async(Dispatchers.Default) { settings.setDefaultPromotionFolder(folder) }
                    }.awaitAll()
                }
                val result = settings.defaultPromotionFolder.first()

                assertSoftly {
                    results shouldContainExactly List(16) { Unit }
                    folders shouldContain result
                }
                result
            }

            val result = withSettingsStore(file) { it.defaultPromotionFolder.first() }

            result shouldBe committed
        }
    }

    @Nested
    inner class Failures {
        @Test
        fun `on a blocked storage parent reading reports an error instead of unset`() = withSettingsFile { file ->
            val blocker = Files.writeString(file.parent.resolve("blocked"), "retained")

            val result = shouldThrow<IOException> {
                withSettingsStore(blocker.resolve("settings.preferences_pb")) { it.defaultPromotionFolder.first() }
            }

            result.message.isNullOrBlank() shouldBe false
        }

        @Test
        fun `on corrupt storage reading reports corruption and leaves the file intact`() = withSettingsFile { file ->
            val corrupt = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
            Files.write(file, corrupt)

            val error = shouldThrow<CorruptionException> {
                withSettingsStore(file) { it.defaultPromotionFolder.first() }
            }
            val result = Files.readAllBytes(file)

            assertSoftly {
                error.message.isNullOrBlank() shouldBe false
                result.toList() shouldContainExactly corrupt.toList()
            }
        }

        @Test
        fun `on a blocked storage parent saving fails without claiming a folder`() = withSettingsFile { file ->
            val blocker = Files.writeString(file.parent.resolve("blocked"), "retained")
            val settingsFile = blocker.resolve("settings.preferences_pb")

            val error = shouldThrow<IOException> {
                withSettingsStore(settingsFile) {
                    it.setDefaultPromotionFolder(file.parent.resolve("Games").toString())
                }
            }
            val result = Files.readString(blocker)

            assertSoftly {
                error.message.isNullOrBlank() shouldBe false
                result shouldBe "retained"
            }
        }

        @Test
        fun `on delete-locked storage failed changes retain the previous folder and saving can recover`() =
            withSettingsFile { file ->
                assumeTrue(System.getProperty("os.name").startsWith("Windows"))
                val original = file.parent.resolve("Games").toString()
                val replacement = file.parent.resolve("OtherGames").toString()
                val recovered = file.parent.resolve("RecoveredGames").toString()
                withSettingsStore(file) { settings ->
                    settings.setDefaultPromotionFolder(original)
                    coroutineScope {
                        val observations = Channel<String?>(Channel.UNLIMITED)
                        val observer = launch(start = UNDISPATCHED) {
                            settings.defaultPromotionFolder.collect { observations.send(it) }
                        }
                        try {
                            val initial = observations.receive()
                            FileChannel.open(file, READ, NOSHARE_DELETE).use {
                                val updateError = shouldThrow<IOException> {
                                    settings.setDefaultPromotionFolder(replacement)
                                }
                                val afterUpdate = settings.defaultPromotionFolder.first()
                                val clearError = shouldThrow<IOException> { settings.clearDefaultPromotionFolder() }
                                val result = settings.defaultPromotionFolder.first()

                                assertSoftly {
                                    updateError.message.isNullOrBlank() shouldBe false
                                    clearError.message.isNullOrBlank() shouldBe false
                                    afterUpdate shouldBe original
                                    result shouldBe original
                                }
                            }
                            val saved = settings.setDefaultPromotionFolder(recovered)
                            val observed = observations.receive()
                            val extra = observations.tryReceive()

                            assertSoftly {
                                initial shouldBe original
                                saved shouldBe Unit
                                observed shouldBe recovered
                                extra.isFailure shouldBe true
                            }
                        } finally {
                            observer.cancelAndJoin()
                        }
                    }
                }

                val result = withSettingsStore(file) { it.defaultPromotionFolder.first() }

                result shouldBe recovered
            }

        @Test
        fun `on read-locked storage reading fails and can be retried after unlocking`() = withSettingsFile { file ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val folder = file.parent.resolve("Games").toString()
            withSettingsStore(file) { it.setDefaultPromotionFolder(folder) }
            withSettingsStore(file) { settings ->
                FileChannel.open(file, READ, NOSHARE_READ).use {
                    val result = shouldThrow<IOException> { settings.defaultPromotionFolder.first() }

                    result.message.isNullOrBlank() shouldBe false
                }

                val result = settings.defaultPromotionFolder.first()

                result shouldBe folder
            }
        }
    }

    @Nested
    inner class Filesystems {
        @TestFactory
        fun `on saving and clearing each filesystem retains committed preferences`() = testVolumeCases { root ->
            runBlocking {
                val file = root.resolve("GameTier/settings.preferences_pb")
                val folder = root.resolve("Games").toString()
                withSettingsStore(file) { it.setDefaultPromotionFolder(folder) }
                val saved = withSettingsStore(file) { it.defaultPromotionFolder.first() }
                withSettingsStore(file) { it.clearDefaultPromotionFolder() }
                val cleared = withSettingsStore(file) { it.defaultPromotionFolder.first() }

                assertSoftly {
                    saved shouldBe folder
                    cleared shouldBe null
                }
            }
        }
    }
}
