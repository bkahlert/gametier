package com.bkahlert.gametier

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class FileMoveVolumesTest {
    @Test
    fun `on an unrelated fixture directory provisioning refuses to modify it`() = withMoveDirectory { root ->
        if (!System.getProperty("os.name").startsWith("Windows")) return@withMoveDirectory
        val sentinel = Files.writeString(root.resolve("keep.txt"), "untouched")
        val helper = projectRoot().resolve("scripts").resolve("FileMoveVolumes.ps1")

        val result = runMovePowerShell(
            "-File", helper.toString(), "-Action", "Setup", "-FixtureDirectory", root.toString(),
            "-UserSid", "S-1-5-18",
        )

        result.first shouldBe 1
        result.second shouldContain "fixture"
        Files.readString(sentinel) shouldBe "untouched"
        Files.list(root).use { it.count() } shouldBe 1L
    }

    @Test
    fun `on an unrelated fixture directory cleanup refuses to delete files`() = withMoveDirectory { root ->
        if (!System.getProperty("os.name").startsWith("Windows")) return@withMoveDirectory
        val sentinel = Files.writeString(root.resolve("ntfs-1.vhdx"), "untouched")
        val helper = projectRoot().resolve("scripts").resolve("FileMoveVolumes.ps1")

        val result = runMovePowerShell(
            "-File", helper.toString(), "-Action", "Cleanup", "-FixtureDirectory", root.toString(),
            "-UserSid", "S-1-5-18",
        )

        result.first shouldBe 1
        result.second shouldContain "fixture"
        Files.readString(sentinel) shouldBe "untouched"
    }

    @Test
    fun `on a matching directory name an unrecorded image is retained`() = withMoveDirectory { root ->
        if (!System.getProperty("os.name").startsWith("Windows")) return@withMoveDirectory
        val fixture = Files.createDirectory(root.resolve("gametier-volumes-" + "a".repeat(32)))
        val image = Files.writeString(fixture.resolve("ntfs-1.vhdx"), "unrelated image")
        val helper = projectRoot().resolve("scripts").resolve("FileMoveVolumes.ps1")

        val result = runMovePowerShell(
            "-File", helper.toString(), "-Action", "Cleanup", "-FixtureDirectory", fixture.toString(),
            "-UserSid", "S-1-5-18",
        )

        result.first shouldBe 1
        result.second shouldContain "ownership"
        Files.readString(image) shouldBe "unrelated image"
    }
}

private fun projectRoot(): Path {
    var path: Path? = Path.of("").toAbsolutePath()
    while (path != null && !Files.exists(path.resolve("project.yaml"))) path = path.parent
    return requireNotNull(path) { "Cannot locate project.yaml" }
}

private fun runMovePowerShell(vararg arguments: String): Pair<Int, String> {
    val process = ProcessBuilder(
        listOf("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass") +
            arguments,
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    return process.waitFor() to output
}
