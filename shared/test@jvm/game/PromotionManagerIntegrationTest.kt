package com.bkahlert.gametier.game

import com.bkahlert.gametier.settings.withSettingsStore
import com.bkahlert.gametier.settings.DataStoreAppSettings
import com.bkahlert.gametier.settings.MemoryPreferencesStore
import com.bkahlert.gametier.testsupport.filesystem.assertTestTree
import com.bkahlert.gametier.testsupport.filesystem.createTestTree
import com.bkahlert.gametier.testsupport.filesystem.requireSymlinkSupport
import com.bkahlert.gametier.testsupport.filesystem.testVolumeCases
import com.bkahlert.gametier.testsupport.filesystem.testVolumePairCases
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory

class PromotionManagerIntegrationTest {
    @Nested
    inner class Persistence {
        @TestFactory
        fun `on each filesystem registration and unmatched content survive reopening`() = testVolumeCases { root ->
            runBlocking {
                val folder = Files.createDirectory(root.resolve("games"))
                val extra = Files.writeString(folder.resolve("unrelated"), "retained")
                val settingsFile = root.resolve("settings.preferences_pb")
                val registered = withSettingsStore(settingsFile) { settings ->
                    PromotionManager(settings).registerDirectory(folder)
                }

                val result = withSettingsStore(settingsFile) { settings -> PromotionManager(settings).inventory() }

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
        val manager = PromotionManager(settings)
        originals.forEach { manager.promote(it, folder) }

        val promoted = PromotionManager(settings).inventory().single()
        val demoted = originals.map { PromotionManager(settings).demote(folder, it.fileName.toString()) }
        val result = PromotionManager(settings).inventory().single()

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
