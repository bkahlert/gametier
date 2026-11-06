package com.bkahlert.gametier.files

import com.bkahlert.gametier.testsupport.filesystem.assertTestTree
import com.bkahlert.gametier.testsupport.filesystem.configuredTestVolumes
import com.bkahlert.gametier.testsupport.filesystem.createTestTree
import com.bkahlert.gametier.testsupport.filesystem.withTestVolumeDirectory
import com.bkahlert.gametier.testsupport.filesystem.withTestVolumePairs

import com.sun.nio.file.ExtendedOpenOption
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.FileTime
import kotlin.test.Test

class FileMoverIntegrationTest {
    @Test
    fun `on all filesystem pairings byte totals include alternate streams and advance within files`() {
        withTestVolumePairs { sourceRoot, destinationRoot ->
            val source = Files.createDirectory(sourceRoot.resolve("game"))
            val bytes = ByteArray(8 * 1024 * 1024) { (it % 251).toByte() }
            val file = Files.write(source.resolve("large.dat"), bytes)
            File("$file:metadata").writeText("stream data")
            Files.writeString(source.resolve("small.dat"), "data")
            val destination = destinationRoot.resolve("game")
            val progress = mutableListOf<TransferProgress>()

            val result = FileMover.move(source, destination, progress::add)

            assertSoftly {
                result shouldBe Unit
                val copying = progress.filter { it.phase == TransferPhase.TRANSFERRING }
                val total = bytes.size + 15L
                copying.map { it.totalBytes }.distinct() shouldBe listOf(total)
                copying.any { it.transferredBytes in 1 until bytes.size.toLong() } shouldBe true
                copying.map { it.transferredBytes }.zipWithNext().all { (a, b) -> a <= b } shouldBe true
                progress.last() shouldBe TransferProgress(TransferPhase.FINALIZING, total, total)
                Files.readAllBytes(destination.resolve("large.dat")).contentEquals(bytes) shouldBe true
                File("${destination.resolve("large.dat")}:metadata").readText() shouldBe "stream data"
                Files.exists(source) shouldBe false
            }
        }
    }

    @Test
    fun `on all filesystem pairings empty trees have stages without invented bytes`() {
        withTestVolumePairs { sourceRoot, destinationRoot ->
            val source = Files.createDirectory(sourceRoot.resolve("empty"))
            Files.createFile(source.resolve("empty.dat"))
            val progress = mutableListOf<TransferProgress>()

            val result = FileMover.move(source, destinationRoot.resolve("empty"), progress::add)

            assertSoftly {
                result shouldBe Unit
                progress.all { it.totalBytes == 0L && it.transferredBytes == 0L } shouldBe true
                progress.last().phase shouldBe TransferPhase.FINALIZING
            }
        }
    }

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
