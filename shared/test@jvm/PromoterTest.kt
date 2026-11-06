package com.bkahlert.gametier

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag

class PromoterTest {
    @Nested
    @Tag("symlink-success")
    inner class Promotion {
        @Test
        fun `on files and directories data stays accessible at the original path`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            listOf(Files.writeString(root.resolve("game.rom"), "game data"), createTestTree(root.resolve("game")))
                .forEach { original ->
                    val destination = root.resolve("promoted-${original.fileName}")
                    val entryCount = Files.list(root).use { it.count() }

                    val result = Promoter.promote(original, destination)

                    assertSoftly {
                        result shouldBe Unit
                        Files.isSymbolicLink(original) shouldBe true
                        Files.readSymbolicLink(original) shouldBe destination.toRealPath()
                        if (Files.isDirectory(destination)) {
                            assertTestTree(original)
                            assertTestTree(destination)
                        } else {
                            Files.readString(original) shouldBe "game data"
                            Files.readString(destination) shouldBe "game data"
                        }
                        Files.list(root).use { it.count() } shouldBe entryCount + 1L
                    }
                    Files.delete(original)
                    deleteTestFixture(destination)
                }
        }

        @Test
        fun `on relative paths parents are normalized`() = withTestDirectory(Path.of("")) { root ->
            requireSymlinkSupport(root)
            val original = Files.writeString(root.resolve("game.rom"), "game data")
            val destination = root.resolve("promoted.rom")
            val cwd = Path.of("").toAbsolutePath()

            val result = Promoter.promote(cwd.relativize(original), cwd.relativize(root.resolve("unused/../promoted.rom")))

            assertSoftly {
                result shouldBe Unit
                Files.readSymbolicLink(original) shouldBe destination.toRealPath()
                Files.readString(original) shouldBe "game data"
            }
        }

        @Test
        fun `on parent aliases the stored link targets the canonical destination`() = withTestDirectory { root ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            requireSymlinkSupport(root)
            val parent = Files.createDirectory(root.resolve("parent"))
            val alias = root.resolve("alias")
            createTestJunction(alias, parent)
            val original = Files.writeString(parent.resolve("game.rom"), "game data")
            val destination = parent.resolve("promoted.rom")

            val result = Promoter.promote(alias.resolve("game.rom"), alias.resolve("promoted.rom"))

            assertSoftly {
                result shouldBe Unit
                Files.readSymbolicLink(original) shouldBe destination.toRealPath()
                Files.readString(alias.resolve("game.rom")) shouldBe "game data"
                Files.readString(destination) shouldBe "game data"
            }
        }

        @Test
        fun `on repeated promotion the existing link and game remain`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val original = Files.writeString(root.resolve("game.rom"), "game data")
            val destination = root.resolve("promoted.rom")
            Promoter.promote(original, destination)

            val result = shouldThrow<IOException> { Promoter.promote(original, root.resolve("another.rom")) }

            assertTransferFailure(result, original, root.resolve("another.rom"), "validation")
            Files.readSymbolicLink(original) shouldBe destination.toRealPath()
            Files.readString(destination) shouldBe "game data"
        }
    }

    @Nested
    inner class Validation {
        @Test
        fun `on invalid paths no source data changes`() = withTestDirectory { root ->
            val original = Files.writeString(root.resolve("game.rom"), "game data")
            val directory = createTestTree(root.resolve("game"))
            val occupied = Files.writeString(root.resolve("occupied"), "existing")
            listOf(
                original to occupied, original to directory, original to original,
                directory to directory.resolve("nested/destination"),
                original to root.resolve("missing/destination"), root.resolve("missing/source") to root.resolve("new"),
                root.resolve("missing") to root.resolve("new"), root.root to root.resolve("new"),
                original to root.root,
            ).forEach { (source, destination) ->
                val result = shouldThrow<IOException> { Promoter.promote(source, destination) }

                assertSoftly {
                    assertTransferFailure(result, source, destination, "validation")
                    Files.readString(original) shouldBe "game data"
                    Files.readString(occupied) shouldBe "existing"
                    assertTestTree(directory)
                    Files.exists(root.resolve("missing")) shouldBe false
                    Files.exists(root.resolve("new")) shouldBe false
                    Files.list(root).use { it.count() } shouldBe 3L
                }
            }
        }

        @Test
        fun `on different filesystem providers no move starts`() = withTestDirectory { root ->
            val original = Files.writeString(root.resolve("game.rom"), "game data")
            withTestArchive(root) { archive ->
                val destination = archive.resolve("game.rom")

                val result = shouldThrow<IOException> { Promoter.promote(original, destination) }

                assertTransferFailure(result, original, destination, "validation")
                Files.readString(original) shouldBe "game data"
                Files.exists(destination) shouldBe false
            }
        }

        @Test
        fun `on a junction source or collision no data changes`() = withTestDirectory { root ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val original = createTestTree(root.resolve("game"))
            val junction = root.resolve("junction")
            createTestJunction(junction, original)
            listOf(junction to root.resolve("new"), original to junction, original to junction.resolve("nested/new"))
                .forEach { (source, destination) ->
                    val result = shouldThrow<IOException> { Promoter.promote(source, destination) }

                    assertTransferFailure(result, source, destination, "validation")
                    assertTestTree(original)
                    isWindowsReparsePoint(junction) shouldBe true
                    Files.exists(root.resolve("new")) shouldBe false
                }
        }

        @Test
        @Tag("symlink-success")
        fun `on nested links and dangling collisions validation leaves entries intact`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val original = createTestTree(root.resolve("game"))
            val nested = Files.createSymbolicLink(original.resolve("link"), root.resolve("missing"))
            val occupied = Files.createSymbolicLink(root.resolve("occupied"), root.resolve("missing"))
            listOf(root.resolve("destination"), occupied).forEach { destination ->
                val result = shouldThrow<IOException> { Promoter.promote(original, destination) }

                assertTransferFailure(result, original, destination, "validation")
                Files.isSymbolicLink(nested) shouldBe true
                Files.isSymbolicLink(occupied) shouldBe true
                Files.readString(original.resolve("game.dat")) shouldBe "game data"
            }
        }
    }

    @Nested
    inner class Preflight {
        @Test
        fun `on unavailable symlinks no data moves`() = withTestDirectory { root ->
            val original = Files.writeString(root.resolve("game.rom"), "game data")
            val destination = root.resolve("promoted.rom")
            val failure = IOException("symlinks unavailable")
            val operations = GameFileOperations(createLink = { _, _ -> throw failure })

            val result = shouldThrow<IOException> { Promoter.promote(original, destination, operations) }

            assertSoftly {
                assertTransferFailure(result, original, destination, "preflight")
                result.cause shouldBe failure
                Files.readString(original) shouldBe "game data"
                Files.exists(destination) shouldBe false
                Files.list(root).use { it.count() } shouldBe 1L
            }
        }

        @Test
        fun `on preflight name collision the other entry is preserved`() = withTestDirectory { root ->
            val original = Files.writeString(root.resolve("game.rom"), "game data")
            val destination = root.resolve("promoted.rom")
            var collision: Path? = null
            val operations = GameFileOperations(createLink = { link, _ ->
                collision = Files.writeString(link, "other entry")
                throw java.nio.file.FileAlreadyExistsException(link.toString())
            })

            val result = shouldThrow<IOException> { Promoter.promote(original, destination, operations) }

            assertTransferFailure(result, original, destination, "preflight")
            Files.readString(collision!!) shouldBe "other entry"
            Files.readString(original) shouldBe "game data"
            Files.exists(destination) shouldBe false
        }

        @Test
        fun `on an unsupported provider the original stays accessible`() = withTestDirectory { root ->
            withTestArchive(root) { archive ->
                val original = Files.writeString(archive.resolve("game.rom"), "game data")
                val destination = archive.resolve("promoted.rom")

                val result = shouldThrow<IOException> { Promoter.promote(original, destination) }

                assertTransferFailure(result, original, destination, "preflight")
                Files.readString(original) shouldBe "game data"
                Files.exists(destination) shouldBe false
            }
        }
    }

    @Nested
    @Tag("symlink-success")
    inner class Recovery {
        @Test
        fun `on a move that changes paths before throwing no unproven rollback runs`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val original = Files.writeString(root.resolve("game.rom"), "game data")
            val destination = root.resolve("promoted.rom")
            val failure = IOException("move did not report success")
            val operations = GameFileOperations(move = { from, to ->
                FileMover.move(from, to)
                throw failure
            })

            val result = shouldThrow<IOException> { Promoter.promote(original, destination, operations) }

            assertTransferFailure(result, original, destination, "move")
            result.cause shouldBe failure
            Files.exists(original, NOFOLLOW_LINKS) shouldBe false
            Files.readString(destination) shouldBe "game data"
        }

        @Test
        fun `on failed preflight cleanup the leftover link is reported before moving`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val original = Files.writeString(root.resolve("game.rom"), "game data")
            val destination = root.resolve("promoted.rom")
            val failure = IOException("cannot remove probe")
            var probe: Path? = null
            val operations = GameFileOperations(delete = { probe = it; throw failure })

            val result = shouldThrow<IOException> { Promoter.promote(original, destination, operations) }

            assertTransferFailure(result, original, destination, "preflight")
            result.message.orEmpty() shouldContain probe.toString()
            result.cause shouldBe failure
            Files.isSymbolicLink(probe!!) shouldBe true
            Files.readString(original) shouldBe "game data"
            Files.exists(destination) shouldBe false
        }

        @Test
        fun `on partial move failure all surviving data is retained`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val original = createTestTree(root.resolve("game"))
            val destination = root.resolve("promoted")
            val failure = IOException("partial move")
            val operations = GameFileOperations(move = { _, to ->
                Files.createDirectory(to)
                Files.writeString(to.resolve("partial"), "copied data")
                throw failure
            })

            val result = shouldThrow<IOException> { Promoter.promote(original, destination, operations) }

            assertTransferFailure(result, original, destination, "move")
            result.cause shouldBe failure
            result.suppressed.toList() shouldBe emptyList()
            assertTestTree(original)
            Files.readString(destination.resolve("partial")) shouldBe "copied data"
            Files.isSymbolicLink(original) shouldBe false
        }

        @Test
        fun `on final link failure the completed move is reversed`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val original = createTestTree(root.resolve("game"))
            val destination = root.resolve("promoted")
            val failure = IOException("final link failed")
            val operations = GameFileOperations(createLink = { link, target ->
                if (link == original) throw failure
                SymlinkCreator.create(link, target)
            })

            val result = shouldThrow<IOException> { Promoter.promote(original, destination, operations) }

            assertSoftly {
                assertTransferFailure(result, original, destination, "link creation")
                result.cause shouldBe failure
                result.suppressed.toList() shouldBe emptyList()
                assertTestTree(original)
                Files.isSymbolicLink(original) shouldBe false
                Files.exists(destination, NOFOLLOW_LINKS) shouldBe false
                Files.list(root).use { it.count() } shouldBe 1L
            }
        }

        @Test
        fun `on rollback failure the relocated game and both errors survive`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val original = Files.writeString(root.resolve("game.rom"), "game data")
            val destination = root.resolve("promoted.rom")
            val failure = IOException("final link failed")
            val recoveryFailure = IOException("rollback failed")
            val operations = GameFileOperations(
                move = { from, to -> if (from == destination) throw recoveryFailure else FileMover.move(from, to) },
                createLink = { link, target -> if (link == original) throw failure else SymlinkCreator.create(link, target) },
            )

            val result = shouldThrow<IOException> { Promoter.promote(original, destination, operations) }

            assertTransferFailure(result, original, destination, "link creation")
            result.cause shouldBe failure
            result.suppressed.toList() shouldBe listOf(recoveryFailure)
            Files.exists(original, NOFOLLOW_LINKS) shouldBe false
            Files.readString(destination) shouldBe "game data"
        }

        @Test
        fun `on a new occupant rollback never overwrites it`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val original = Files.writeString(root.resolve("game.rom"), "game data")
            val destination = root.resolve("promoted.rom")
            val operations = GameFileOperations(createLink = { link, target ->
                if (link == original) {
                    Files.writeString(link, "new occupant")
                    throw IOException("occupied")
                }
                SymlinkCreator.create(link, target)
            })

            val result = shouldThrow<IOException> { Promoter.promote(original, destination, operations) }

            assertTransferFailure(result, original, destination, "link creation")
            Files.readString(original) shouldBe "new occupant"
            Files.readString(destination) shouldBe "game data"
        }
    }
}
