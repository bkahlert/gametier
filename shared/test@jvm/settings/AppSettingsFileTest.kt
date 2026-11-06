package com.bkahlert.gametier.settings

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.test.Test

class AppSettingsFileTest {
    @Test
    fun `on local app data the settings file belongs to GameTier`() {
        val localAppData = Path.of("Local AppData").toAbsolutePath()

        val result = appSettingsFile(localAppData.toString())

        result shouldBe localAppData.resolve("GameTier/settings.preferences_pb")
    }

    @Test
    fun `on unavailable or relative local app data initialization fails`() {
        listOf(null, "", " \t", "relative").forEach { localAppData ->
            val result = shouldThrow<IllegalStateException> { appSettingsFile(localAppData) }

            result.message.isNullOrBlank() shouldBe false
        }
    }

    @Test
    fun `on malformed local app data initialization fails`() {
        val result = shouldThrow<InvalidPathException> { appSettingsFile("bad\u0000path") }

        result.input shouldBe "bad\u0000path"
    }
}
