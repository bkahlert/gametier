package com.bkahlert.gametier.testsupport.workflow

import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.test.Test

class VolumeFixturesTest {
    @Test
    fun `on an unrelated fixture directory provisioning refuses to modify it`() = withTestDirectory { root ->
        if (!System.getProperty("os.name").startsWith("Windows")) return@withTestDirectory
        val sentinel = Files.writeString(root.resolve("keep.txt"), "untouched")
        val helper = projectRoot().resolve("scripts").resolve("VolumeFixtures.ps1")

        val result = runTestPowerShell(
            "-File", helper.toString(), "-Action", "Setup", "-FixtureDirectory", root.toString(),
            "-UserSid", "S-1-5-18",
        )

        result.first shouldBe 1
        result.second shouldContain "fixture"
        Files.readString(sentinel) shouldBe "untouched"
        Files.list(root).use { it.count() } shouldBe 1L
    }

    @Test
    fun `on an unrelated fixture directory cleanup refuses to delete files`() = withTestDirectory { root ->
        if (!System.getProperty("os.name").startsWith("Windows")) return@withTestDirectory
        val sentinel = Files.writeString(root.resolve("ntfs-1.vhdx"), "untouched")
        val helper = projectRoot().resolve("scripts").resolve("VolumeFixtures.ps1")

        val result = runTestPowerShell(
            "-File", helper.toString(), "-Action", "Cleanup", "-FixtureDirectory", root.toString(),
            "-UserSid", "S-1-5-18",
        )

        result.first shouldBe 1
        result.second shouldContain "fixture"
        Files.readString(sentinel) shouldBe "untouched"
    }

    @Test
    fun `on a matching directory name an unrecorded image is retained`() = withTestDirectory { root ->
        if (!System.getProperty("os.name").startsWith("Windows")) return@withTestDirectory
        val fixture = Files.createDirectory(root.resolve("gametier-volumes-" + "a".repeat(32)))
        val image = Files.writeString(fixture.resolve("ntfs-1.vhdx"), "unrelated image")
        val helper = projectRoot().resolve("scripts").resolve("VolumeFixtures.ps1")

        val result = runTestPowerShell(
            "-File", helper.toString(), "-Action", "Cleanup", "-FixtureDirectory", fixture.toString(),
            "-UserSid", "S-1-5-18",
        )

        result.first shouldBe 1
        result.second shouldContain "ownership"
        Files.readString(image) shouldBe "unrelated image"
    }
}
