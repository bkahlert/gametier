package com.bkahlert.gametier.game

import com.bkahlert.gametier.settings.withSettingsStore
import com.bkahlert.gametier.files.TransferPhase
import com.bkahlert.gametier.files.TransferProgress
import com.bkahlert.gametier.settings.DataStoreAppSettings
import com.bkahlert.gametier.settings.MemoryPreferencesStore
import com.bkahlert.gametier.testsupport.filesystem.assertTestTree
import com.bkahlert.gametier.testsupport.filesystem.createTestTree
import com.bkahlert.gametier.testsupport.filesystem.requireSymlinkSupport
import com.bkahlert.gametier.testsupport.filesystem.testVolumeCases
import com.bkahlert.gametier.testsupport.filesystem.testVolumePairCases
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory

class PromotionManagerIntegrationTest {
    @Nested
    inner class Progress {
        @TestFactory
        fun `on final link failure across volumes recovery renames the source snapshot`() = testVolumePairCases { from, to ->
            runBlocking {
                val original = Files.write(from.resolve("game.rom"), ByteArray(3 * 1024 * 1024))
                val updates = mutableListOf<TransferProgress>()
                val failure = IOException("final link failed")
                val manager = isolatedPromotionManager(from, DataStoreAppSettings(MemoryPreferencesStore()), GameFileOperations(
                    createLink = { link, _ -> if (link == original) throw failure else Files.writeString(link, "probe") },
                ), PromotionIndex())

                val result = shouldThrow<IOException> { manager.promote(original, to, updates::add) }

                assertSoftly {
                    generateSequence(result as Throwable) { it.cause }.last() shouldBe failure
                    val forward = updates.filter { !it.recovering && it.phase == TransferPhase.TRANSFERRING }
                    val recovery = updates.filter { it.recovering && it.phase == TransferPhase.TRANSFERRING }
                    forward.first().transferredBytes shouldBe 0L
                    recovery shouldBe emptyList()
                    forward.last().transferredBytes shouldBe 3L * 1024 * 1024
                    updates.any { it.recovering && it.phase == TransferPhase.MOVING } shouldBe true
                    Files.size(original) shouldBe 3L * 1024 * 1024
                    Files.exists(to.resolve("game.rom")) shouldBe false
                }
            }
        }
    }

    @Nested
    inner class Persistence {
        @TestFactory
        fun `on each filesystem registration and unmatched content survive reopening`() = testVolumeCases { root ->
            runBlocking {
                val folder = Files.createDirectory(root.resolve("games"))
                val extra = Files.writeString(folder.resolve("unrelated"), "retained")
                val settingsFile = root.resolve("settings.preferences_pb")
                val registered = withSettingsStore(settingsFile) { settings ->
                    isolatedPromotionManager(root, settings).registerDirectory(folder)
                }

                val result = withSettingsStore(settingsFile) { settings -> isolatedPromotionManager(root, settings).inventory() }

                assertSoftly {
                    registered shouldBe Unit
                    result.single().directory shouldBe folder
                    result.single().status shouldBe DestinationStatus.AVAILABLE
                    result.single().unrecognized shouldBe listOf(extra)
                    Files.isHidden(folder.resolve(".gametier.json")) shouldBe true
                }
            }
        }
    }

    @Nested
    @Tag("symlink-success")
    inner class Transfers {
        @TestFactory
        fun `on each volume file and directory round trips remain discoverable`() = testVolumeCases { root ->
            assertIndexedRoundTrip(root, root)
        }

        @TestFactory
        fun `on cross volume file and directory round trips origins survive reopening`() = testVolumePairCases { from, to ->
            assertIndexedRoundTrip(from, to)
        }
    }
}

private fun assertIndexedRoundTrip(from: java.nio.file.Path, to: java.nio.file.Path) {
    requireSymlinkSupport(from)
    runBlocking {
        val folder = Files.createDirectory(to.resolve("games"))
        val originals = listOf(Files.writeString(from.resolve("game.rom"), "game data"), createTestTree(from.resolve("game")))
        val settings = DataStoreAppSettings(MemoryPreferencesStore())
        val manager = isolatedPromotionManager(from, settings)
        originals.forEach { manager.promote(it, folder) }

        val promoted = isolatedPromotionManager(from, settings).inventory().single()
        val demoted = originals.map { isolatedPromotionManager(from, settings).demote(folder, it.fileName.toString()) }
        val result = isolatedPromotionManager(from, settings).inventory().single()

        assertSoftly {
            promoted.promotions.map { it.condition } shouldBe List(2) { PromotionCondition.PROMOTED }
            promoted.promotions.map { it.original } shouldBe originals
            demoted shouldBe List(2) { Unit }
            result.promotions shouldBe emptyList()
            result.unrecognized shouldBe emptyList()
            Files.readString(originals.first()) shouldBe "game data"
            assertTestTree(originals.last())
        }
    }
}
