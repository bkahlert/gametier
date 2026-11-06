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
import kotlin.test.Test

class FileMoverIntegrationTest {
    @Test
    fun `on moving files across all filesystem pairings contents and attributes are retained`() {
        withTestVolumePairs { sourceRoot, destinationRoot ->
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
        withTestVolumePairs { sourceRoot, destinationRoot ->
            val source = createTestTree(sourceRoot.resolve("game"))
            val destination = destinationRoot.resolve("game")

            val result = FileMover.move(source, destination)

            result shouldBe Unit
            Files.exists(source) shouldBe false
            assertTestTree(destination)
        }
    }

    @Test
    fun `on moving within each volume files and trees are relocated`() {
        configuredTestVolumes().forEach { volume ->
            withTestVolumeDirectory(volume) { root ->
                val source = createTestTree(root.resolve("game"))
                val destination = root.resolve("moved")

                val result = FileMover.move(source, destination)

                result shouldBe Unit
                Files.exists(source) shouldBe false
                assertTestTree(destination)
                val fileResult = FileMover.move(destination.resolve("game.dat"), root.resolve("game.dat"))
                fileResult shouldBe Unit
                Files.exists(destination.resolve("game.dat")) shouldBe false
                Files.readString(root.resolve("game.dat")) shouldBe "game data"
            }
        }
    }

    @Test
    fun `on destination collisions across all pairings neither tree is modified`() {
        withTestVolumePairs { sourceRoot, destinationRoot ->
            val source = createTestTree(sourceRoot.resolve("game"))
            val destination = createTestTree(destinationRoot.resolve("game"))

            val result = shouldThrow<IOException> { FileMover.move(source, destination) }

            result.message.orEmpty() shouldContain "validation"
            assertTestTree(source)
            assertTestTree(destination)
        }
    }

    @Test
    fun `on a locked source copy failure retains the source and reports the partial destination`() {
        withTestVolumePairs { sourceRoot, destinationRoot ->
            val source = createTestTree(sourceRoot.resolve("game"))
            val destination = destinationRoot.resolve("game")
            FileChannel.open(source.resolve("game.dat"), READ, ExtendedOpenOption.NOSHARE_READ).use {
                val result = shouldThrow<IOException> { FileMover.move(source, destination) }

                result.message.orEmpty() shouldContain "copy"
                result.message.orEmpty() shouldContain "Source retained"
                Files.isDirectory(destination) shouldBe true
            }
            assertTestTree(source)
        }
    }

    @Test
    fun `on a delete-locked source cleanup failure retains the copied destination`() {
        withTestVolumePairs { sourceRoot, destinationRoot ->
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
