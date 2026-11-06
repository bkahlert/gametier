package com.bkahlert.gametier.game

import com.bkahlert.gametier.testsupport.filesystem.createTestJunction
import com.bkahlert.gametier.testsupport.filesystem.requireSymlinkSupport
import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.test.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag

class PromotionInventoryTest {
    @Nested
    inner class Metadata {
        @Test
        fun `on missing corrupt and unavailable metadata inventory reports uncertainty`() = withTestDirectory { root ->
            Files.writeString(root.resolve("unrelated"), "retained")

            val missing = PromotionInventory.inspect(root)
            val unavailable = PromotionInventory.inspect(root.resolve("offline"))
            val index = Files.writeString(root.resolve(".gametier.json"), "damaged")
            val corrupt = PromotionInventory.inspect(root)

            assertSoftly {
                missing.status shouldBe DestinationStatus.MISSING_INDEX
                missing.unrecognized shouldBe emptyList()
                unavailable.status shouldBe DestinationStatus.UNAVAILABLE
                corrupt.status shouldBe DestinationStatus.INVALID_INDEX
                corrupt.unrecognized shouldBe emptyList()
                Files.readString(index) shouldBe "damaged"
                Files.readString(root.resolve("unrelated")) shouldBe "retained"
            }
        }

        @Test
        fun `on unmatched files and directories only direct children are reported`() = withTestDirectory { root ->
            val game = Files.createDirectory(root.resolve("game"))
            Files.writeString(game.resolve("part.dat"), "part")
            val extra = Files.createDirectory(root.resolve("other"))
            Files.writeString(extra.resolve("nested"), "other")
            val loose = Files.writeString(root.resolve("loose.rom"), "loose")
            PromotionIndex().write(root, listOf(IndexedPromotion("game", root.resolve("missing-origin"), TransferState.PROMOTED)))

            val result = PromotionInventory.inspect(root)

            assertSoftly {
                result.status shouldBe DestinationStatus.AVAILABLE
                result.unrecognized.toSet() shouldBe setOf(extra, loose)
                result.promotions.single().condition shouldBe PromotionCondition.NEEDS_ATTENTION
            }
        }

        @Test
        fun `on interrupted promotion or demotion the intent survives absent game data`() = withTestDirectory { root ->
            PromotionIndex().write(root, listOf(
                IndexedPromotion("first", root.resolve("origin-first"), TransferState.PROMOTING),
                IndexedPromotion("second", root.resolve("origin-second"), TransferState.DEMOTING),
            ))

            val result = PromotionInventory.inspect(root)

            assertSoftly {
                result.promotions.map { it.condition } shouldBe List(2) { PromotionCondition.NEEDS_ATTENTION }
                result.promotions.map { it.state } shouldBe listOf(TransferState.PROMOTING, TransferState.DEMOTING)
                result.unrecognized shouldBe emptyList()
            }
        }

        @Test
        fun `on junction metadata the external directory remains untouched`() = withTestDirectory { root ->
            val external = Files.createDirectory(root.resolve("external"))
            val directory = Files.createDirectory(root.resolve("games"))
            createTestJunction(directory.resolve(".gametier.json"), external)

            val result = PromotionInventory.inspect(directory)

            result.status shouldBe DestinationStatus.INVALID_INDEX
            Files.list(external).use { it.count() } shouldBe 0L
        }
    }

    @Nested
    @Tag("symlink-success")
    inner class Relationships {
        @Test
        fun `on valid redirected and dangling links health follows the filesystem`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val directory = Files.createDirectory(root.resolve("games"))
            val target = Files.writeString(directory.resolve("game"), "data")
            val original = Files.createSymbolicLink(root.resolve("origin"), target)
            PromotionIndex().write(directory, listOf(IndexedPromotion("game", original, TransferState.PROMOTED)))

            val valid = PromotionInventory.inspect(directory)
            Files.delete(original)
            val other = Files.writeString(root.resolve("other"), "unrelated")
            Files.createSymbolicLink(original, other)
            val redirected = PromotionInventory.inspect(directory)
            Files.delete(target)
            val missing = PromotionInventory.inspect(directory)

            assertSoftly {
                valid.promotions.single().condition shouldBe PromotionCondition.PROMOTED
                redirected.promotions.single().condition shouldBe PromotionCondition.NEEDS_ATTENTION
                missing.promotions.single().condition shouldBe PromotionCondition.NEEDS_ATTENTION
                Files.readString(other) shouldBe "unrelated"
            }
        }
    }
}
