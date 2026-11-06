package com.bkahlert.gametier

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test

class FileMoverTest {
    @Test
    fun `on moving a file its contents and timestamp are retained`() = withMoveDirectory { root ->
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
    fun `on moving a tree nested files and empty directories are retained`() = withMoveDirectory { root ->
        val source = createMoveTree(root.resolve("source"))
        val destination = root.resolve("destination")

        val result = FileMover.move(source, destination)

        result shouldBe Unit
        Files.exists(source) shouldBe false
        assertMoveTree(destination)
    }

    @Test
    fun `on moving across filesystems the entire tree is copied before source removal`() =
        withMoveDirectory { root ->
            withMoveArchive(root) { archive ->
                val source = createMoveTree(root.resolve("source"))
                val destination = archive.resolve("destination")

                val result = FileMover.move(source, destination)

                result shouldBe Unit
                Files.exists(source) shouldBe false
                assertMoveTree(destination)
            }
        }

    @Test
    fun `on moving a file across filesystems its timestamp is retained`() = withMoveDirectory { root ->
        withMoveArchive(root) { archive ->
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
    fun `on an existing destination neither file is changed`() = withMoveDirectory { root ->
        val source = Files.writeString(root.resolve("source"), "source")
        val destination = Files.writeString(root.resolve("destination"), "existing")

        val result = shouldThrow<IOException> { FileMover.move(source, destination) }

        result.message.orEmpty() shouldContain "validation"
        Files.readString(source) shouldBe "source"
        Files.readString(destination) shouldBe "existing"
    }

    @Test
    fun `on an existing directory destination no merge occurs`() = withMoveDirectory { root ->
        val source = createMoveTree(root.resolve("source"))
        val destination = Files.createDirectory(root.resolve("destination"))

        val result = shouldThrow<IOException> { FileMover.move(source, destination) }

        result.message.orEmpty() shouldContain "validation"
        assertMoveTree(source)
        Files.list(destination).use { it.count() } shouldBe 0L
    }

    @Test
    fun `on a missing source destination is not created`() = withMoveDirectory { root ->
        val source = root.resolve("missing")
        val destination = root.resolve("destination")

        val result = shouldThrow<IOException> { FileMover.move(source, destination) }

        result.message.orEmpty() shouldContain "validation"
        Files.exists(destination) shouldBe false
    }

    @Test
    fun `on a destination inside the source no entries are changed`() = withMoveDirectory { root ->
        val source = createMoveTree(root.resolve("source"))
        val destination = source.resolve("nested").resolve("destination")

        val result = shouldThrow<IOException> { FileMover.move(source, destination) }

        result.message.orEmpty() shouldContain "validation"
        assertMoveTree(source)
        Files.exists(destination) shouldBe false
    }

    @Test
    fun `on a missing destination parent the source remains`() = withMoveDirectory { root ->
        val source = createMoveTree(root.resolve("source"))
        val destination = root.resolve("missing").resolve("destination")

        val result = shouldThrow<IOException> { FileMover.move(source, destination) }

        result.message.orEmpty() shouldContain "validation"
        assertMoveTree(source)
        Files.exists(destination) shouldBe false
    }

    @Test
    fun `on a read-only destination filesystem a failed copy retains the source`() =
        withMoveDirectory { root ->
            val archivePath = root.resolve("readonly.zip")
            withMoveArchive(root, "readonly.zip") { archive ->
                Files.writeString(archive.resolve("existing"), "existing")
            }
            FileSystems.newFileSystem(archivePath, mapOf("accessMode" to "readOnly")).use { archive ->
                val source = createMoveTree(root.resolve("source"))
                val destination = archive.getPath("/destination")

                val result = shouldThrow<IOException> { FileMover.move(source, destination) }

                result.message.orEmpty() shouldContain "copy"
                assertMoveTree(source)
                Files.exists(destination) shouldBe false
            }

        }

    @Test
    fun `on source cleanup failure the copied destination remains`() = withMoveDirectory { root ->
        withMoveArchive(root, "readonly.zip") { archive ->
            createMoveTree(archive.resolve("source"))
        }
        FileSystems.newFileSystem(root.resolve("readonly.zip"), mapOf("accessMode" to "readOnly")).use { archive ->
            val source = archive.getPath("/source")
            val destination = root.resolve("destination")

            val result = shouldThrow<IOException> { FileMover.move(source, destination) }

            result.message.orEmpty() shouldContain "source cleanup"
            result.message.orEmpty() shouldContain "incomplete"
            assertMoveTree(source)
            assertMoveTree(destination)
        }
    }

    @Test
    fun `on a junction in the source no target is moved`() = withMoveDirectory { root ->
        if (!System.getProperty("os.name").startsWith("Windows")) return@withMoveDirectory
        val source = Files.createDirectory(root.resolve("source"))
        val target = createMoveTree(root.resolve("target"))
        val link = source.resolve("link")
        createMoveJunction(link, target)
        val destination = root.resolve("destination")

        val result = shouldThrow<IOException> { FileMover.move(source, destination) }

        result.message.orEmpty() shouldContain "Unsupported"
        Files.exists(link, NOFOLLOW_LINKS) shouldBe true
        assertMoveTree(target)
        Files.exists(destination) shouldBe false
    }

    @Test
    fun `on a dangling destination junction no overwrite occurs`() = withMoveDirectory { root ->
        if (!System.getProperty("os.name").startsWith("Windows")) return@withMoveDirectory
        val source = createMoveTree(root.resolve("source"))
        val target = Files.createDirectory(root.resolve("target"))
        val destination = root.resolve("destination")
        createMoveJunction(destination, target)
        Files.delete(target)

        val result = shouldThrow<IOException> { FileMover.move(source, destination) }

        result.message.orEmpty() shouldContain "already exists"
        assertMoveTree(source)
        isWindowsReparsePoint(destination) shouldBe true
    }

    @Test
    fun `on a destination alias inside the source no mutation occurs`() = withMoveDirectory { root ->
        if (!System.getProperty("os.name").startsWith("Windows")) return@withMoveDirectory
        val source = createMoveTree(root.resolve("source"))
        val alias = root.resolve("alias")
        createMoveJunction(alias, source.resolve("nested"))
        val destination = alias.resolve("destination")

        val result = shouldThrow<IOException> { FileMover.move(source, destination) }

        result.message.orEmpty() shouldContain "inside"
        assertMoveTree(source)
        Files.exists(destination) shouldBe false
    }
}

internal fun withMoveDirectory(action: (Path) -> Unit) {
    val root = Files.createTempDirectory("gametier-move-")
    try {
        action(root)
    } finally {
        deleteMoveFixture(root)
    }
}

internal fun deleteMoveFixture(root: Path) {
    if (Files.exists(root, NOFOLLOW_LINKS)) {
        Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes):
                java.nio.file.FileVisitResult {
                if (isWindowsReparsePoint(dir)) {
                    Files.delete(dir)
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE
                }
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes):
                java.nio.file.FileVisitResult {
                Files.getFileAttributeView(file, java.nio.file.attribute.DosFileAttributeView::class.java)
                    ?.setReadOnly(false)
                Files.delete(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): java.nio.file.FileVisitResult {
                if (!isWindowsReparsePoint(file)) throw exc
                Files.delete(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): java.nio.file.FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }
}

internal fun withMoveArchive(root: Path, name: String = "destination.zip", action: (Path) -> Unit) =
    FileSystems.newFileSystem(URI.create("jar:${root.resolve(name).toUri()}"), mapOf("create" to "true")).use {
        action(it.getPath("/"))
    }

internal fun createMoveTree(path: Path): Path {
    Files.createDirectories(path.resolve("nested").resolve("empty"))
    Files.writeString(path.resolve("game.dat"), "game data")
    Files.writeString(path.resolve("nested").resolve("config.ini"), "settings")
    return path
}

internal fun assertMoveTree(path: Path) {
    Files.readString(path.resolve("game.dat")) shouldBe "game data"
    Files.readString(path.resolve("nested").resolve("config.ini")) shouldBe "settings"
    Files.isDirectory(path.resolve("nested").resolve("empty")) shouldBe true
    Files.walk(path).use { it.count() } shouldBe 5L
}

internal fun createMoveJunction(link: Path, target: Path) {
    val process = ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), target.toString())
        .redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (process.waitFor() != 0) throw IOException("Could not create test junction: $output")
}
