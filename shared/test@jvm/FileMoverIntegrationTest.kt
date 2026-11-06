package com.bkahlert.gametier

import com.sun.nio.file.ExtendedOpenOption
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.FileTime
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test

class FileMoverIntegrationTest {
    @Test
    fun `on moving files across all filesystem pairings contents and attributes are retained`() {
        withVolumePairs { sourceRoot, destinationRoot ->
            val source = Files.writeString(sourceRoot.resolve("game.dat"), "game data")
            val destination = destinationRoot.resolve("game.dat")
            val time = FileTime.fromMillis(1_600_000_000_000)
            Files.setLastModifiedTime(source, time)
            Files.getFileAttributeView(source, DosFileAttributeView::class.java).apply {
                setHidden(true)
                setReadOnly(true)
            }

            val result = FileMover.move(source, destination)

            result shouldBe Unit
            Files.exists(source) shouldBe false
            Files.readString(destination) shouldBe "game data"
            Files.getLastModifiedTime(destination) shouldBe time
            Files.readAttributes(destination, java.nio.file.attribute.DosFileAttributes::class.java).apply {
                isHidden shouldBe true
                isReadOnly shouldBe true
            }
        }
    }

    @Test
    fun `on moving trees across all filesystem pairings source entries are removed`() {
        withVolumePairs { sourceRoot, destinationRoot ->
            val source = createMoveTree(sourceRoot.resolve("game"))
            val destination = destinationRoot.resolve("game")

            val result = FileMover.move(source, destination)

            result shouldBe Unit
            Files.exists(source) shouldBe false
            assertMoveTree(destination)
        }
    }

    @Test
    fun `on moving within each volume files and trees are relocated`() {
        configuredMoveVolumes().forEach { volume ->
            withVolumeDirectory(volume) { root ->
                val source = createMoveTree(root.resolve("game"))
                val destination = root.resolve("moved")

                val result = FileMover.move(source, destination)

                result shouldBe Unit
                Files.exists(source) shouldBe false
                assertMoveTree(destination)
                val fileResult = FileMover.move(destination.resolve("game.dat"), root.resolve("game.dat"))
                fileResult shouldBe Unit
                Files.exists(destination.resolve("game.dat")) shouldBe false
                Files.readString(root.resolve("game.dat")) shouldBe "game data"
            }
        }
    }

    @Test
    fun `on destination collisions across all pairings neither tree is modified`() {
        withVolumePairs { sourceRoot, destinationRoot ->
            val source = createMoveTree(sourceRoot.resolve("game"))
            val destination = createMoveTree(destinationRoot.resolve("game"))

            val result = shouldThrow<IOException> { FileMover.move(source, destination) }

            result.message.orEmpty() shouldContain "validation"
            assertMoveTree(source)
            assertMoveTree(destination)
        }
    }

    @Test
    fun `on a locked source copy failure retains the source and reports the partial destination`() {
        withVolumePairs { sourceRoot, destinationRoot ->
            val source = createMoveTree(sourceRoot.resolve("game"))
            val destination = destinationRoot.resolve("game")
            FileChannel.open(source.resolve("game.dat"), READ, ExtendedOpenOption.NOSHARE_READ).use {
                val result = shouldThrow<IOException> { FileMover.move(source, destination) }

                result.message.orEmpty() shouldContain "copy"
                result.message.orEmpty() shouldContain "Source retained"
                Files.isDirectory(destination) shouldBe true
            }
            assertMoveTree(source)
        }
    }

    @Test
    fun `on a delete-locked source cleanup failure retains the copied destination`() {
        withVolumePairs { sourceRoot, destinationRoot ->
            val source = Files.writeString(sourceRoot.resolve("game.dat"), "game data")
            val destination = destinationRoot.resolve("game.dat")
            FileChannel.open(source, READ, ExtendedOpenOption.NOSHARE_DELETE).use {
                val result = shouldThrow<IOException> { FileMover.move(source, destination) }

                result.message.orEmpty() shouldContain "source cleanup"
                Files.readString(source) shouldBe "game data"
                Files.readString(destination) shouldBe "game data"
            }
        }
    }
}

private fun configuredMoveVolumes(): List<Path> {
    val configuration = System.getenv("GAMETIER_TEST_VOLUMES")
    assumeTrue(configuration != null, "Run scripts\\Test-FileMoves.ps1 to enable real-filesystem tests")
    val paths = configuration.split(';').map { Path.of(it).toRealPath() }
    require(paths.size == 4) { "GAMETIER_TEST_VOLUMES must contain NTFS1;NTFS2;ReFS1;ReFS2 roots" }
    val stores = paths.map { Files.getFileStore(it) }
    require(stores.map { it.type().uppercase() } == listOf("NTFS", "NTFS", "REFS", "REFS")) {
        "Expected two NTFS and two ReFS volumes, got ${stores.map { it.type() }}"
    }
    require(stores.toSet().size == 4) { "Four distinct volumes are required" }
    return paths
}

private fun withVolumePairs(action: (Path, Path) -> Unit) {
    val roots = configuredMoveVolumes()
    listOf(0 to 1, 0 to 2, 2 to 3, 2 to 0).forEach { (from, to) ->
        withVolumeDirectory(roots[from]) { source ->
            withVolumeDirectory(roots[to]) { destination ->
                action(source, destination)
            }
        }
    }
}

private fun withVolumeDirectory(volume: Path, action: (Path) -> Unit) {
    val root = Files.createTempDirectory(volume, "gametier-move-")
    try {
        action(root)
    } finally {
        deleteMoveFixture(root)
    }
}
