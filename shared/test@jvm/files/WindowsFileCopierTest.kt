package com.bkahlert.gametier.files

import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

class WindowsFileCopierTest {
    @Test
    fun `on callback failure copying stops and the source remains`() = withTestDirectory { root ->
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val source = Files.write(root.resolve("source"), ByteArray(8 * 1024 * 1024))
        val destination = root.resolve("destination")
        val failure = AssertionError("progress failed")

        val result = shouldThrow<IOException> {
            WindowsFileCopier.copy(source, destination) { if (it > 0) throw failure }
        }

        assertSoftly {
            result.cause shouldBe failure
            Files.size(source) shouldBe 8L * 1024 * 1024
        }
    }

    @Test
    fun `on native copying progress arrives before the file completes and includes alternate streams`() = withTestDirectory { root ->
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val bytes = ByteArray(8 * 1024 * 1024) { (it % 251).toByte() }
        val source = Files.write(root.resolve("source"), bytes)
        File("$source:metadata").writeText("stream data")
        val destination = root.resolve("destination")
        val progress = mutableListOf<Long>()

        val total = WindowsFileCopier.size(source)
        val result = WindowsFileCopier.copy(source, destination, progress::add)

        assertSoftly {
            result shouldBe Unit
            total shouldBe bytes.size + 11L
            progress.any { it in 1 until bytes.size.toLong() } shouldBe true
            progress.zipWithNext().all { (a, b) -> a <= b } shouldBe true
            progress.last() shouldBe total
            Files.readAllBytes(destination).contentEquals(bytes) shouldBe true
            File("$destination:metadata").readText() shouldBe "stream data"
            Files.exists(source) shouldBe true
        }
    }

    @Test
    fun `on a native collision the destination survives`() = withTestDirectory { root ->
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val source = Files.writeString(root.resolve("source"), "data")
        val destination = Files.writeString(root.resolve("destination"), "existing")

        val result = shouldThrow<IOException> { WindowsFileCopier.copy(source, destination) {} }

        assertSoftly {
            result.message.isNullOrBlank() shouldBe false
            Files.readString(destination) shouldBe "existing"
            Files.readString(source) shouldBe "data"
        }
    }
}
