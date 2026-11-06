package com.bkahlert.gametier.game

import com.bkahlert.gametier.files.SymlinkCreator
import com.bkahlert.gametier.files.isWindowsReparsePoint
import com.bkahlert.gametier.testsupport.filesystem.assertTestTree
import com.bkahlert.gametier.testsupport.filesystem.createTestJunction
import com.bkahlert.gametier.testsupport.filesystem.createTestTree
import com.bkahlert.gametier.testsupport.filesystem.requireSymlinkSupport
import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag

class DemoterTest {
    @Nested
    @Tag("symlink-success")
    inner class Demotion {
        @Test
        fun `on files and directories the original entry replaces its link`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            listOf(Files.writeString(root.resolve("rom-target"), "game data"), createTestTree(root.resolve("tree-target")))
                .forEach { target ->
                    val original = root.resolve("original-${target.fileName}")
                    SymlinkCreator.create(original, target)

                    val result = Demoter.demote(original)

                    assertSoftly {
                        result shouldBe Unit
                        Files.isSymbolicLink(original) shouldBe false
                        Files.exists(target, NOFOLLOW_LINKS) shouldBe false
                        if (Files.isDirectory(original)) assertTestTree(original)
                        else Files.readString(original) shouldBe "game data"
                    }
                }
        }

        @Test
        fun `on relative stored targets resolution starts at the link parent`() = withTestDirectory(Path.of("")) { root ->
            requireSymlinkSupport(root)
            val target = Files.writeString(root.resolve("game.rom"), "game data")
            val parent = Files.createDirectory(root.resolve("links"))
            val original = Files.createSymbolicLink(parent.resolve("game.rom"), Path.of("../game.rom"))
            val relative = Path.of("").toAbsolutePath().relativize(original)

            val result = Demoter.demote(relative)

            assertSoftly {
                result shouldBe Unit
                Files.readString(original) shouldBe "game data"
                Files.isSymbolicLink(original) shouldBe false
                Files.exists(target, NOFOLLOW_LINKS) shouldBe false
            }
        }

        @Test
        fun `on parent aliases relative targets use the physical link parent`() = withTestDirectory { root ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            requireSymlinkSupport(root)
            val parent = Files.createDirectory(root.resolve("parent"))
            val aliases = Files.createDirectory(root.resolve("aliases"))
            val alias = aliases.resolve("alias")
            createTestJunction(alias, parent)
            val target = Files.writeString(root.resolve("game.rom"), "game data")
            val original = Files.createSymbolicLink(parent.resolve("game.rom"), Path.of("../game.rom"))

            val result = Demoter.demote(alias.resolve("game.rom"))

            assertSoftly {
                result shouldBe Unit
                Files.isSymbolicLink(original) shouldBe false
                Files.readString(original) shouldBe "game data"
                Files.exists(target, NOFOLLOW_LINKS) shouldBe false
            }
        }

        @Test
        fun `on repeated demotion the restored game remains intact`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val target = Files.writeString(root.resolve("target"), "game data")
            val original = root.resolve("original")
            SymlinkCreator.create(original, target)
            Demoter.demote(original)

            val result = shouldThrow<IOException> { Demoter.demote(original) }

            assertTransferFailure(result, original, null, "validation")
            Files.readString(original) shouldBe "game data"
        }
    }

    @Nested
    inner class Validation {
        @Test
        fun `on missing entries roots and real games nothing is removed`() = withTestDirectory { root ->
            val file = Files.writeString(root.resolve("game.rom"), "game data")
            val directory = createTestTree(root.resolve("game"))
            listOf(root.resolve("missing"), root.resolve("missing/link"), file, directory, root.root).forEach { original ->
                val result = shouldThrow<IOException> { Demoter.demote(original) }

                assertTransferFailure(result, original, null, "validation")
                Files.readString(file) shouldBe "game data"
                assertTestTree(directory)
            }
        }

        @Test
        fun `on junctions the entry and target remain`() = withTestDirectory { root ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val target = createTestTree(root.resolve("target"))
            val original = root.resolve("original")
            createTestJunction(original, target)

            val result = shouldThrow<IOException> { Demoter.demote(original) }

            assertTransferFailure(result, original, null, "validation")
            isWindowsReparsePoint(original) shouldBe true
            assertTestTree(target)
        }

        @Test
        @Tag("symlink-success")
        fun `on dangling links chains and linked contents the original link remains`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val game = createTestTree(root.resolve("game"))
            Files.createSymbolicLink(game.resolve("nested-link"), root.resolve("missing"))
            val chain = Files.createSymbolicLink(root.resolve("chain"), game)
            listOf(root.resolve("missing"), chain, game, root.root).forEachIndexed { index, target ->
                val original = Files.createSymbolicLink(root.resolve("original-$index"), target)

                val result = shouldThrow<IOException> { Demoter.demote(original) }

                assertSoftly {
                    assertTransferFailure(result, original, target, "validation")
                    Files.readSymbolicLink(original) shouldBe target
                    Files.readString(game.resolve("game.dat")) shouldBe "game data"
                    Files.isSymbolicLink(chain) shouldBe true
                }
            }
        }

        @Test
        @Tag("symlink-success")
        fun `on an original link inside its target no deletion occurs`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val target = createTestTree(root.resolve("game"))
            val original = Files.createSymbolicLink(target.resolve("original"), target)

            val result = shouldThrow<IOException> { Demoter.demote(original) }

            assertTransferFailure(result, original, target, "validation")
            Files.readSymbolicLink(original) shouldBe target
            Files.readString(target.resolve("game.dat")) shouldBe "game data"
        }
    }

    @Nested
    @Tag("symlink-success")
    inner class Recovery {
        @Test
        fun `on failed link removal target data is untouched`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val target = Files.writeString(root.resolve("target"), "game data")
            val original = root.resolve("original")
            SymlinkCreator.create(original, target)
            val failure = IOException("unlink failed")
            val operations = GameFileOperations(delete = { throw failure })

            val result = shouldThrow<IOException> { Demoter.demote(original, operations) }

            assertTransferFailure(result, original, target, "link removal")
            result.cause shouldBe failure
            Files.readSymbolicLink(original) shouldBe target.toRealPath()
            Files.readString(target) shouldBe "game data"
        }

        @Test
        fun `on a failed move access is restored through a link`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val target = createTestTree(root.resolve("target"))
            val original = root.resolve("original")
            SymlinkCreator.create(original, target)
            val failure = IOException("move failed")
            val operations = GameFileOperations(move = { _, _ -> throw failure })

            val result = shouldThrow<IOException> { Demoter.demote(original, operations) }

            assertSoftly {
                assertTransferFailure(result, original, target, "move")
                result.cause shouldBe failure
                result.suppressed.toList() shouldBe emptyList()
                Files.readSymbolicLink(original) shouldBe target.toRealPath()
                assertTestTree(original)
                assertTestTree(target)
            }
        }

        @Test
        fun `on failed link recovery the target and both failures survive`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val target = Files.writeString(root.resolve("target"), "game data")
            val original = root.resolve("original")
            SymlinkCreator.create(original, target)
            val failure = IOException("move failed")
            val recoveryFailure = IOException("restore link failed")
            val operations = GameFileOperations(
                move = { _, _ -> throw failure },
                createLink = { _, _ -> throw recoveryFailure },
            )

            val result = shouldThrow<IOException> { Demoter.demote(original, operations) }

            assertTransferFailure(result, original, target, "move")
            result.cause shouldBe failure
            result.suppressed.toList() shouldBe listOf(recoveryFailure)
            Files.exists(original, NOFOLLOW_LINKS) shouldBe false
            Files.readString(target) shouldBe "game data"
        }

        @Test
        fun `on a partial destination both copies are preserved without a replacement link`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val target = createTestTree(root.resolve("target"))
            val original = root.resolve("original")
            SymlinkCreator.create(original, target)
            val operations = GameFileOperations(move = { _, to ->
                Files.createDirectory(to)
                Files.writeString(to.resolve("partial"), "copied data")
                throw IOException("copy failed")
            })

            val result = shouldThrow<IOException> { Demoter.demote(original, operations) }

            assertTransferFailure(result, original, target, "move")
            Files.isSymbolicLink(original) shouldBe false
            Files.readString(original.resolve("partial")) shouldBe "copied data"
            assertTestTree(target)
        }

        @Test
        fun `on a vanished or replaced target recovery creates no dangling link or chain`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            listOf(false, true).forEachIndexed { index, replace ->
                val target = Files.writeString(root.resolve("target-$index"), "game data")
                val original = root.resolve("original-$index")
                SymlinkCreator.create(original, target)
                val operations = GameFileOperations(move = { from, _ ->
                    Files.delete(from)
                    if (replace) Files.createSymbolicLink(from, root.resolve("missing"))
                    throw IOException("target changed")
                })

                val result = shouldThrow<IOException> { Demoter.demote(original, operations) }

                assertTransferFailure(result, original, target, "move")
                Files.exists(original, NOFOLLOW_LINKS) shouldBe false
                Files.isSymbolicLink(target) shouldBe replace
            }
        }
    }
}
