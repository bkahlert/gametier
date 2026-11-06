package com.bkahlert.gametier.files

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.io.TempDir
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test

class StageClasspathTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `on colliding dependency names all contents are preserved`() {
        val application = fixture("app/main.jar", "app")
        val first = fixture("first/runtime.jar", "first")
        val second = fixture("second/runtime.jar", "second")
        val output = root.resolve("output")

        stageClasspath(application, listOf(application, first, second), output)
        val result = output.listDirectoryEntries()

        assertSoftly {
            result.map { it.fileName.toString() }.distinct().size shouldBe 3
            result.map { it.readText() } shouldContainExactlyInAnyOrder listOf("app", "first", "second")
            output.resolve("GameTier.jar").readText() shouldBe "app"
        }
    }

    @Test
    fun `on restaging removed dependencies are absent`() {
        val application = fixture("app/main.jar", "app")
        val dependency = fixture("lib/runtime.jar", "dependency")
        val output = root.resolve("output")
        stageClasspath(application, listOf(application, dependency), output)

        stageClasspath(application, listOf(application), output)
        val result = output.listDirectoryEntries()

        result.map { it.fileName.toString() } shouldContainExactlyInAnyOrder listOf("GameTier.jar")
    }

    @Test
    fun `on a missing dependency staging fails`() {
        val application = fixture("app/main.jar", "app")
        val missing = root.resolve("missing.jar")

        val result = shouldThrow<NoSuchFileException> {
            stageClasspath(application, listOf(application, missing), root.resolve("output"))
        }

        result.file shouldBe missing.toString()
    }

    private fun fixture(name: String, content: String): Path = root.resolve(name).also {
        it.parent.createDirectories()
        it.writeText(content)
    }
}
