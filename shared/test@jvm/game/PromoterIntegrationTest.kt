package com.bkahlert.gametier.game

import com.bkahlert.gametier.files.FileMover
import com.bkahlert.gametier.files.SymlinkCreator
import com.bkahlert.gametier.testsupport.filesystem.assertTestTree
import com.bkahlert.gametier.testsupport.filesystem.createTestTree
import com.bkahlert.gametier.testsupport.filesystem.requireSymlinkSupport
import com.bkahlert.gametier.testsupport.filesystem.symlinkCreationFailure
import com.bkahlert.gametier.testsupport.filesystem.testVolumeCases
import com.bkahlert.gametier.testsupport.filesystem.testVolumePairCases

import com.sun.nio.file.ExtendedOpenOption
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory

class PromoterIntegrationTest {
    @Nested
    @Tag("symlink-success")
    inner class Promotion {
        @TestFactory
        fun `on final link failure across volumes rollback restores the game`() = testVolumePairCases { from, to ->
            requireSymlinkSupport(from)
            val original = createTestTree(from.resolve("game"))
            val destination = to.resolve("game")
            val failure = IOException("final link failed")
            val operations = GameFileOperations(createLink = { link, target ->
                if (link == original) throw failure else SymlinkCreator.create(link, target)
            })

            val result = shouldThrow<IOException> { Promoter.promote(original, destination, operations) }

            assertTransferFailure(result, original, destination, "link creation")
            result.cause shouldBe failure
            result.suppressed.toList() shouldBe emptyList()
            Files.isSymbolicLink(original) shouldBe false
            assertTestTree(original)
            Files.exists(destination, NOFOLLOW_LINKS) shouldBe false
        }

        @TestFactory
        fun `on rollback cleanup failure across volumes both copies survive`() = testVolumePairCases { from, to ->
            requireSymlinkSupport(from)
            val original = Files.writeString(from.resolve("game.rom"), "game data")
            val destination = to.resolve("game.rom")
            val failure = IOException("final link failed")
            val operations = GameFileOperations(
                move = { source, target ->
                    if (source == destination) {
                        FileChannel.open(source, READ, ExtendedOpenOption.NOSHARE_DELETE).use {
                            FileMover.move(source, target)
                        }
                    } else FileMover.move(source, target)
                },
                createLink = { link, target ->
                    if (link == original) throw failure else SymlinkCreator.create(link, target)
                },
            )

            val result = shouldThrow<IOException> { Promoter.promote(original, destination, operations) }

            assertTransferFailure(result, original, destination, "link creation")
            result.cause shouldBe failure
            result.suppressed.size shouldBe 1
            Files.isSymbolicLink(original) shouldBe false
            Files.readString(original) shouldBe "game data"
            Files.readString(destination) shouldBe "game data"
        }

        @TestFactory
        fun `on files across volumes original paths access relocated data`() = testVolumePairCases { from, to ->
            assertPromotion(from, to, false)
        }

        @TestFactory
        fun `on trees across volumes original paths access relocated data`() = testVolumePairCases { from, to ->
            assertPromotion(from, to, true)
        }

        @TestFactory
        fun `on each volume files and trees can be promoted`() = testVolumeCases { root ->
            assertPromotion(root, root, false)
            assertPromotion(root, root, true)
        }

        @TestFactory
        fun `on copy failure source and partial destination survive`() = testVolumePairCases { from, to ->
            requireSymlinkSupport(from)
            val original = createTestTree(from.resolve("game"))
            val destination = to.resolve("game")
            FileChannel.open(original.resolve("game.dat"), READ, ExtendedOpenOption.NOSHARE_READ).use {
                val result = shouldThrow<IOException> { Promoter.promote(original, destination) }

                assertTransferFailure(result, original, destination, "move")
                Files.isSymbolicLink(original) shouldBe false
                Files.isDirectory(destination) shouldBe true
            }
            assertTestTree(original)
        }

        @TestFactory
        fun `on cleanup failure both data files survive`() = testVolumePairCases { from, to ->
            requireSymlinkSupport(from)
            val original = Files.writeString(from.resolve("game.rom"), "game data")
            val destination = to.resolve("game.rom")
            FileChannel.open(original, READ, ExtendedOpenOption.NOSHARE_DELETE).use {
                val result = shouldThrow<IOException> { Promoter.promote(original, destination) }

                assertTransferFailure(result, original, destination, "move")
                Files.isSymbolicLink(original) shouldBe false
                Files.readString(original) shouldBe "game data"
                Files.readString(destination) shouldBe "game data"
            }
        }
    }

    @Nested
    inner class Preflight {
        @TestFactory
        fun `on unavailable privileges data stays at the original volume`() = testVolumePairCases { from, to ->
            val unavailable = symlinkCreationFailure(from)
            org.junit.jupiter.api.Assumptions.assumeTrue(unavailable != null, "Symlink support is available")
            val original = createTestTree(from.resolve("game"))
            val destination = to.resolve("game")

            val result = shouldThrow<IOException> { Promoter.promote(original, destination) }

            assertTransferFailure(result, original, destination, "preflight")
            assertTestTree(original)
            Files.exists(destination, NOFOLLOW_LINKS) shouldBe false
            Files.list(from).use { it.count() } shouldBe 1L
        }
    }
}

private fun assertPromotion(from: Path, to: Path, directory: Boolean) {
    requireSymlinkSupport(from)
    val name = if (directory) "game" else "game.rom"
    val original = if (directory) createTestTree(from.resolve(name))
        else Files.writeString(from.resolve(name), "game data")
    val destination = to.resolve("promoted-$name")

    val result = Promoter.promote(original, destination)

    assertSoftly {
        result shouldBe Unit
        Files.isSymbolicLink(original) shouldBe true
        Files.readSymbolicLink(original) shouldBe destination.toRealPath()
        if (directory) {
            assertTestTree(original)
            assertTestTree(destination)
        } else {
            Files.readString(original) shouldBe "game data"
            Files.readString(destination) shouldBe "game data"
        }
    }
}
