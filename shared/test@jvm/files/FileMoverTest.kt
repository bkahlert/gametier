package com.bkahlert.gametier.files

import com.bkahlert.gametier.testsupport.filesystem.assertTestTree
import com.bkahlert.gametier.testsupport.filesystem.createTestJunction
import com.bkahlert.gametier.testsupport.filesystem.createTestTree
import com.bkahlert.gametier.testsupport.filesystem.withTestArchive
import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import org.junit.jupiter.api.Nested

class FileMoverTest {
    @Nested
    inner class Copy {
        @Test
        fun `on a same volume file the source remains independent`() = withTestDirectory { root ->
            val source = Files.writeString(root.resolve("source"), "snapshot")
            val destination = root.resolve("destination")
            val timestamp = FileTime.fromMillis(1_600_000_000_000)
            Files.setLastModifiedTime(source, timestamp)
            val updates = mutableListOf<TransferProgress>()

            val result = FileMover.copy(source, destination, updates::add)

            assertSoftly {
                result shouldBe Unit
                Files.readString(source) shouldBe "snapshot"
                Files.readString(destination) shouldBe "snapshot"
                Files.getLastModifiedTime(destination) shouldBe timestamp
                updates.last() shouldBe TransferProgress(TransferPhase.FINALIZING, 8, 8)
            }
            Files.writeString(destination, "changed")
            Files.readString(source) shouldBe "snapshot"
        }

        @Test
        fun `on a directory nested data and empty directories remain at both locations`() = withTestDirectory { root ->
            val source = createTestTree(root.resolve("source"))
            val destination = root.resolve("destination")

            val result = FileMover.copy(source, destination)

            assertSoftly {
                result shouldBe Unit
                assertTestTree(source)
                assertTestTree(destination)
            }
        }

        @Test
        fun `on source mutation verification fails without deleting either copy`() = withTestDirectory { root ->
            val source = Files.writeString(root.resolve("source"), "before")
            val destination = root.resolve("destination")
            var changed = false

            val result = shouldThrow<IOException> {
                FileMover.copy(source, destination) { progress ->
                    if (!changed && progress.phase == TransferPhase.FINALIZING) {
                        changed = true
                        Files.writeString(source, "new source contents")
                    }
                }
            }

            assertSoftly {
                result.message.orEmpty() shouldContain "Source changed"
                Files.readString(source) shouldBe "new source contents"
                Files.readString(destination) shouldBe "before"
            }
        }

        @Test
        fun `on an observer failure the source is retained`() = withTestDirectory { root ->
            val source = Files.writeString(root.resolve("source"), "snapshot")
            val failure = IOException("observer failed")

            val result = shouldThrow<IOException> {
                FileMover.copy(source, root.resolve("destination")) {
                    if (it.transferredBytes > 0) throw failure
                }
            }

            assertSoftly {
                generateSequence(result as Throwable) { it.cause }.last() shouldBe failure
                Files.readString(source) shouldBe "snapshot"
            }
        }

        @Test
        fun `on an existing destination neither file is overwritten`() = withTestDirectory { root ->
            val source = Files.writeString(root.resolve("source"), "snapshot")
            val destination = Files.writeString(root.resolve("destination"), "occupied")

            val result = shouldThrow<IOException> { FileMover.copy(source, destination) }

            assertSoftly {
                result.message.orEmpty() shouldContain "already exists"
                Files.readString(source) shouldBe "snapshot"
                Files.readString(destination) shouldBe "occupied"
            }
        }

        @Test
        fun `on another provider the source and copied tree both remain`() = withTestDirectory { root ->
            withTestArchive(root) { archive ->
                val source = createTestTree(root.resolve("source"))

                val result = FileMover.copy(source, archive.resolve("destination"))

                assertSoftly {
                    result shouldBe Unit
                    assertTestTree(source)
                    assertTestTree(archive.resolve("destination"))
                }
            }
        }

        @Test
        fun `on junction contents copying refuses to traverse other data`() = withTestDirectory { root ->
            org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val outside = Files.createDirectory(root.resolve("outside"))
            Files.writeString(outside.resolve("save"), "keep")
            val source = Files.createDirectory(root.resolve("source"))
            createTestJunction(source.resolve("nested"), outside)

            val result = shouldThrow<IOException> { FileMover.copy(source, root.resolve("destination")) }

            assertSoftly {
                result.message.orEmpty() shouldContain "Unsupported"
                Files.readString(outside.resolve("save")) shouldBe "keep"
                Files.exists(root.resolve("destination")) shouldBe false
            }
        }
    }

    @Nested
    inner class Deletion {
        @Test
        fun `on changed tree contents guarded cleanup preserves every entry`() = withTestDirectory { root ->
            val tree = createTestTree(root.resolve("tree"))
            val expected = FileMover.snapshot(tree)
            Files.writeString(tree.resolve("new-save"), "new progress")

            val result = shouldThrow<IOException> { FileMover.deleteTree(tree, expected) }

            assertSoftly {
                result.message.orEmpty() shouldContain "changed"
                Files.readString(tree.resolve("new-save")) shouldBe "new progress"
                Files.readString(tree.resolve("game.dat")) shouldBe "game data"
            }
        }

        @Test
        fun `on a replaced root guarded cleanup preserves the replacement`() = withTestDirectory { root ->
            val file = Files.writeString(root.resolve("file"), "before")
            val expected = FileMover.snapshot(file)
            Files.move(file, root.resolve("saved"))
            Files.writeString(file, "replacement")

            val result = shouldThrow<IOException> { FileMover.deleteTree(file, expected) }

            assertSoftly {
                result.message.orEmpty() shouldContain "changed"
                Files.readString(file) shouldBe "replacement"
                Files.readString(root.resolve("saved")) shouldBe "before"
            }
        }

        @Test
        fun `on a redirected parent cleanup refuses the outside tree`() = withTestDirectory { root ->
            org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val outside = Files.createDirectory(root.resolve("outside"))
            val tree = createTestTree(outside.resolve("game"))
            val alias = root.resolve("backup-alias")
            createTestJunction(alias, outside)

            val result = shouldThrow<IOException> { FileMover.deleteTree(alias.resolve("game")) }

            assertSoftly {
                result.message.orEmpty() shouldContain "ordinary directory"
                assertTestTree(tree)
            }
        }

        @Test
        fun `on an ordinary tree read only files can be removed`() = withTestDirectory { root ->
            val tree = createTestTree(root.resolve("tree"))
            Files.getFileAttributeView(tree.resolve("game.dat"), java.nio.file.attribute.DosFileAttributeView::class.java)
                ?.setReadOnly(true)

            val result = FileMover.deleteTree(tree)

            result shouldBe Unit
            Files.exists(tree, NOFOLLOW_LINKS) shouldBe false
        }

        @Test
        fun `on a missing tree deletion fails rather than pretending cleanup succeeded`() = withTestDirectory { root ->
            val result = shouldThrow<IOException> { FileMover.deleteTree(root.resolve("missing")) }

            result.message.orEmpty().isNotBlank() shouldBe true
        }

        @Test
        fun `on nested junctions cleanup refuses before deleting ordinary entries`() = withTestDirectory { root ->
            org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val outside = Files.createDirectory(root.resolve("outside"))
            Files.writeString(outside.resolve("save"), "keep")
            val tree = createTestTree(root.resolve("tree"))
            createTestJunction(tree.resolve("junction"), outside)

            val result = shouldThrow<IOException> { FileMover.deleteTree(tree) }

            assertSoftly {
                result.message.orEmpty() shouldContain "Unsupported"
                Files.readString(tree.resolve("game.dat")) shouldBe "game data"
                Files.readString(tree.resolve("nested/config.ini")) shouldBe "settings"
                Files.exists(tree.resolve("junction"), NOFOLLOW_LINKS) shouldBe true
                Files.readString(outside.resolve("save")) shouldBe "keep"
            }
        }
    }

    @Nested
    inner class Progress {
        @Test
        fun `on a same volume move only stages are reported`() = withTestDirectory { root ->
            val source = Files.writeString(root.resolve("source"), "data")
            val progress = mutableListOf<TransferProgress>()

            val result = FileMover.move(source, root.resolve("destination"), progress::add)

            assertSoftly {
                result shouldBe Unit
                progress shouldBe listOf(TransferProgress(TransferPhase.PREPARING), TransferProgress(TransferPhase.MOVING))
            }
        }

        @Test
        fun `on copying a tree totals stay fixed and advance within a file`() = withTestDirectory { root ->
            withTestArchive(root) { archive ->
                val source = Files.createDirectory(root.resolve("source"))
                Files.write(source.resolve("large"), ByteArray(3 * 1024 * 1024) { (it % 251).toByte() })
                Files.writeString(source.resolve("small"), "data")
                val progress = mutableListOf<TransferProgress>()

                val result = FileMover.move(source, archive.resolve("destination"), progress::add)

                assertSoftly {
                    result shouldBe Unit
                    val copying = progress.filter { it.phase == TransferPhase.TRANSFERRING }
                    copying.map { it.totalBytes }.distinct() shouldBe listOf(3L * 1024 * 1024 + 4)
                    copying.any { it.transferredBytes in 1 until 3L * 1024 * 1024 } shouldBe true
                    copying.map { it.transferredBytes }.zipWithNext().all { (a, b) -> a <= b } shouldBe true
                    progress.last() shouldBe TransferProgress(TransferPhase.FINALIZING, 3L * 1024 * 1024 + 4, 3L * 1024 * 1024 + 4)
                }
            }
        }

        @Test
        fun `on an empty cross volume tree no byte total is invented`() = withTestDirectory { root ->
            withTestArchive(root) { archive ->
                val source = Files.createDirectory(root.resolve("source"))
                Files.createFile(source.resolve("empty"))
                val progress = mutableListOf<TransferProgress>()

                val result = FileMover.move(source, archive.resolve("destination"), progress::add)

                assertSoftly {
                    result shouldBe Unit
                    progress.all { it.totalBytes == 0L && it.transferredBytes == 0L } shouldBe true
                    progress.last().phase shouldBe TransferPhase.FINALIZING
                }
            }
        }

        @Test
        fun `on validation failure progress never announces copying`() = withTestDirectory { root ->
            val progress = mutableListOf<TransferProgress>()

            val result = shouldThrow<IOException> { FileMover.move(root.resolve("missing"), root.resolve("destination"), progress::add) }

            assertSoftly {
                (result.cause is IOException) shouldBe true
                progress shouldBe listOf(TransferProgress(TransferPhase.PREPARING))
            }
        }
    }

    @Test
    fun `on moving a file its contents and timestamp are retained`() = withTestDirectory { root ->
        val source = Files.writeString(root.resolve("source"), "game data")
        val destination = root.resolve("destination")
        val time = FileTime.fromMillis(1_600_000_000_000)
        Files.setLastModifiedTime(source, time)

        val result = FileMover.move(source, destination)

        result shouldBe Unit
        Files.exists(source) shouldBe false
        Files.readString(destination) shouldBe "game data"
        Files.getLastModifiedTime(destination) shouldBe time
    }

    @Test
    fun `on moving a tree nested files and empty directories are retained`() = withTestDirectory { root ->
        val source = createTestTree(root.resolve("source"))
        val destination = root.resolve("destination")

        val result = FileMover.move(source, destination)

        result shouldBe Unit
        Files.exists(source) shouldBe false
        assertTestTree(destination)
    }

    @Test
    fun `on moving across filesystems the entire tree is copied before source removal`() =
        withTestDirectory { root ->
            withTestArchive(root) { archive ->
                val source = createTestTree(root.resolve("source"))
                val destination = archive.resolve("destination")

                val result = FileMover.move(source, destination)

                result shouldBe Unit
                Files.exists(source) shouldBe false
                assertTestTree(destination)
            }
    }

    @Test
    fun `on moving a file across filesystems its timestamp is retained`() = withTestDirectory { root ->
        withTestArchive(root) { archive ->
            val source = Files.writeString(root.resolve("source"), "game data")
            val destination = archive.resolve("destination")
            val time = FileTime.fromMillis(1_600_000_000_000)
            Files.setLastModifiedTime(source, time)

            val result = FileMover.move(source, destination)

            result shouldBe Unit
            Files.exists(source) shouldBe false
            Files.readString(destination) shouldBe "game data"
            Files.getLastModifiedTime(destination) shouldBe time
        }
    }

    @Test
    fun `on an existing destination neither file is changed`() = withTestDirectory { root ->
        val source = Files.writeString(root.resolve("source"), "source")
        val destination = Files.writeString(root.resolve("destination"), "existing")

        val result = shouldThrow<IOException> { FileMover.move(source, destination) }

        result.message.orEmpty() shouldContain "validation"
        Files.readString(source) shouldBe "source"
        Files.readString(destination) shouldBe "existing"
    }

    @Test
    fun `on an existing directory destination no merge occurs`() = withTestDirectory { root ->
        val source = createTestTree(root.resolve("source"))
        val destination = Files.createDirectory(root.resolve("destination"))

        val result = shouldThrow<IOException> { FileMover.move(source, destination) }

        result.message.orEmpty() shouldContain "validation"
        assertTestTree(source)
        Files.list(destination).use { it.count() } shouldBe 0L
    }

    @Test
    fun `on a missing source destination is not created`() = withTestDirectory { root ->
        val source = root.resolve("missing")
        val destination = root.resolve("destination")

        val result = shouldThrow<IOException> { FileMover.move(source, destination) }

        result.message.orEmpty() shouldContain "validation"
        Files.exists(destination) shouldBe false
    }

    @Test
    fun `on a destination inside the source no entries are changed`() = withTestDirectory { root ->
        val source = createTestTree(root.resolve("source"))
        val destination = source.resolve("nested").resolve("destination")

        val result = shouldThrow<IOException> { FileMover.move(source, destination) }

        result.message.orEmpty() shouldContain "validation"
        assertTestTree(source)
        Files.exists(destination) shouldBe false
    }

    @Test
    fun `on a missing destination parent the source remains`() = withTestDirectory { root ->
        val source = createTestTree(root.resolve("source"))
        val destination = root.resolve("missing").resolve("destination")

        val result = shouldThrow<IOException> { FileMover.move(source, destination) }

        result.message.orEmpty() shouldContain "validation"
        assertTestTree(source)
        Files.exists(destination) shouldBe false
    }

    @Test
    fun `on a read-only destination filesystem a failed copy retains the source`() =
        withTestDirectory { root ->
            val archivePath = root.resolve("readonly.zip")
            withTestArchive(root, "readonly.zip") { archive ->
                Files.writeString(archive.resolve("existing"), "existing")
            }
            FileSystems.newFileSystem(archivePath, mapOf("accessMode" to "readOnly")).use { archive ->
                val source = createTestTree(root.resolve("source"))
                val destination = archive.getPath("/destination")

                val result = shouldThrow<IOException> { FileMover.move(source, destination) }

                result.message.orEmpty() shouldContain "copy"
                assertTestTree(source)
                Files.exists(destination) shouldBe false
            }

        }

    @Test
    fun `on source cleanup failure the copied destination remains`() = withTestDirectory { root ->
        withTestArchive(root, "readonly.zip") { archive ->
            createTestTree(archive.resolve("source"))
        }
        FileSystems.newFileSystem(root.resolve("readonly.zip"), mapOf("accessMode" to "readOnly")).use { archive ->
            val source = archive.getPath("/source")
            val destination = root.resolve("destination")

            val result = shouldThrow<IOException> { FileMover.move(source, destination) }

            result.message.orEmpty() shouldContain "source cleanup"
            result.message.orEmpty() shouldContain "incomplete"
            assertTestTree(source)
            assertTestTree(destination)
        }
    }

    @Test
    fun `on a junction in the source no target is moved`() = withTestDirectory { root ->
        if (!System.getProperty("os.name").startsWith("Windows")) return@withTestDirectory
        val source = Files.createDirectory(root.resolve("source"))
        val target = createTestTree(root.resolve("target"))
        val link = source.resolve("link")
        createTestJunction(link, target)
        val destination = root.resolve("destination")

        val result = shouldThrow<IOException> { FileMover.move(source, destination) }

        result.message.orEmpty() shouldContain "Unsupported"
        Files.exists(link, NOFOLLOW_LINKS) shouldBe true
        assertTestTree(target)
        Files.exists(destination) shouldBe false
    }

    @Test
    fun `on a dangling destination junction no overwrite occurs`() = withTestDirectory { root ->
        if (!System.getProperty("os.name").startsWith("Windows")) return@withTestDirectory
        val source = createTestTree(root.resolve("source"))
        val target = Files.createDirectory(root.resolve("target"))
        val destination = root.resolve("destination")
        createTestJunction(destination, target)
        Files.delete(target)

        val result = shouldThrow<IOException> { FileMover.move(source, destination) }

        result.message.orEmpty() shouldContain "already exists"
        assertTestTree(source)
        isWindowsReparsePoint(destination) shouldBe true
    }

    @Test
    fun `on a destination alias inside the source no mutation occurs`() = withTestDirectory { root ->
        if (!System.getProperty("os.name").startsWith("Windows")) return@withTestDirectory
        val source = createTestTree(root.resolve("source"))
        val alias = root.resolve("alias")
        createTestJunction(alias, source.resolve("nested"))
        val destination = alias.resolve("destination")

        val result = shouldThrow<IOException> { FileMover.move(source, destination) }

        result.message.orEmpty() shouldContain "inside"
        assertTestTree(source)
        Files.exists(destination) shouldBe false
    }
}
