package com.bkahlert.gametier.testsupport.workflow

import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BuildUpdateSiteTest {
    @Test
    fun `on staging the version and update URL become build configuration`() = withTestDirectory { root ->
        val (input, library) = inputs(root)
        val destination = root.resolve("update site")

        val result = build("-AppVersion", "1.2.3", "-InputDirectory", input.toString(), "-ExplorerLibrary", library.toString(),
            "-Destination", destination.toString(), "-StageOnly")

        result.first shouldBe 0
        val config = Json.parseToJsonElement(Files.readString(destination.resolve("build.conveyor.conf"))
            .substringAfter("app = ")).jsonObject
        assertSoftly {
            config.getValue("version").jsonPrimitive.content shouldBe "1.2.3"
            config.getValue("site").jsonObject.getValue("base-url").jsonPrimitive.content shouldBe
                "https://github.com/bkahlert/gametier/releases/latest/download"
            config.getValue("site").jsonObject.getValue("consistency-checks").jsonPrimitive.content shouldBe "error"
            Files.exists(destination.resolve("site")) shouldBe false
        }
    }

    @Test
    fun `on disposable test packages remote identity differences warn without changing update URLs`() = withTestDirectory { root ->
        val (input, library) = inputs(root)
        val destination = root.resolve("test site")

        val result = build("-AppVersion", "0.0.1", "-InputDirectory", input.toString(), "-ExplorerLibrary", library.toString(),
            "-Destination", destination.toString(), "-TestPackage", "-StageOnly")

        result.first shouldBe 0
        val config = Json.parseToJsonElement(Files.readString(destination.resolve("build.conveyor.conf"))
            .substringAfter("app = ")).jsonObject
        val site = config.getValue("site").jsonObject
        assertSoftly {
            site.getValue("consistency-checks").jsonPrimitive.content shouldBe "warn"
            site.getValue("base-url").jsonPrimitive.content shouldBe "https://github.com/bkahlert/gametier/releases/latest/download"
        }
    }

    @Test
    fun `on missing staged application packaging leaves the destination absent`() = withTestDirectory { root ->
        val (input, library) = inputs(root)
        Files.delete(input.resolve("GameTier.jar"))

        val result = build("-AppVersion", "1.2.3", "-InputDirectory", input.toString(), "-ExplorerLibrary", library.toString(),
            "-Destination", root.resolve("output").toString(), "-StageOnly")

        assertSoftly {
            result.first shouldBe 1
            result.second shouldContain "Stage GameTier.jar"
            Files.exists(root.resolve("output")) shouldBe false
        }
    }

    @Test
    fun `on an existing destination packaging preserves its contents`() = withTestDirectory { root ->
        val (input, library) = inputs(root)
        val destination = Files.createDirectory(root.resolve("output"))
        val sentinel = Files.writeString(destination.resolve("keep.txt"), "untouched")

        val result = build("-AppVersion", "1.2.3", "-InputDirectory", input.toString(), "-ExplorerLibrary", library.toString(),
            "-Destination", destination.toString(), "-StageOnly")

        assertSoftly {
            result.first shouldBe 1
            result.second shouldContain "destination already exists"
            Files.readString(sentinel) shouldBe "untouched"
            Files.list(destination).use { it.count() } shouldBe 1L
        }
    }

    @Test
    fun `on an out of range package version no output is written`() = withTestDirectory { root ->
        val result = build("-AppVersion", "1.2.65536", "-Destination", root.resolve("output").toString(), "-StageOnly")

        assertSoftly {
            result.first shouldBe 1
            result.second shouldContain "between 0 and 65535"
            Files.exists(root.resolve("output")) shouldBe false
        }
    }

    @Test
    fun `on a non HTTPS remote update site no output is written`() = withTestDirectory { root ->
        val result = build("-AppVersion", "1.2.3", "-UpdateUrl", "http://example.com/updates",
            "-Destination", root.resolve("output").toString(), "-StageOnly")

        assertSoftly {
            result.first shouldBe 1
            result.second shouldContain "HTTPS or localhost"
            Files.exists(root.resolve("output")) shouldBe false
        }
    }

    @Test
    fun `on a missing Explorer library no output is written`() = withTestDirectory { root ->
        val (input, library) = inputs(root)
        Files.delete(library)

        val result = build("-AppVersion", "1.2.3", "-InputDirectory", input.toString(), "-ExplorerLibrary", library.toString(),
            "-Destination", root.resolve("output").toString(), "-StageOnly")

        assertSoftly {
            result.first shouldBe 1
            result.second shouldContain "Build GameTierExplorer.dll"
            Files.exists(root.resolve("output")) shouldBe false
        }
    }

    @Test
    fun `on a release without a persistent key packaging fails before writing output`() = withTestDirectory { root ->
        val result = build("-AppVersion", "1.2.3", "-Destination", root.resolve("output").toString(),
            "-RequireSigningKey", "-StageOnly")

        assertSoftly {
            result.first shouldBe 1
            result.second shouldContain "persistent release key"
            Files.exists(root.resolve("output")) shouldBe false
        }
    }
}

private fun build(vararg arguments: String): Pair<Int, String> {
    val command = ProcessBuilder(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
        projectRoot().resolve("desktopApp/packaging/installer/Build-UpdateSite.ps1").toString()) + arguments)
    command.environment().remove("CONVEYOR_SIGNING_KEY")
    val process = command.redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    return process.waitFor() to output
}

private fun inputs(root: Path): Pair<Path, Path> {
    val input = Files.createDirectory(root.resolve("input jars"))
    Files.write(input.resolve("GameTier.jar"), byteArrayOf())
    return input to Files.write(root.resolve("GameTierExplorer.dll"), byteArrayOf())
}
