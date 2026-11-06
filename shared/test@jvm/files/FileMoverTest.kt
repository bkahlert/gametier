package com.bkahlert.gametier.files

import com.bkahlert.gametier.testsupport.filesystem.assertTestTree
import com.bkahlert.gametier.testsupport.filesystem.createTestJunction
import com.bkahlert.gametier.testsupport.filesystem.createTestTree
import com.bkahlert.gametier.testsupport.filesystem.withTestArchive
import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.FileTime
import kotlin.test.Test

class FileMoverTest {
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
