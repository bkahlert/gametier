package com.bkahlert.gametier.settings

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.IOException
import com.sun.nio.file.ExtendedOpenOption.NOSHARE_READ
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

class LegacyAppSettingsTest {
    @Test
    fun `on first launch after upgrade staged settings survive concurrent migration`() = withSettingsFile { file ->
        val target = appSettingsFile(file.parent.toString())
        val staged = target.resolveSibling("legacy.preferences_pb")
        val folder = file.parent.resolve("Games").toString()
        withSettingsStore(staged) {
            it.setDefaultPromotionFolder(folder)
            it.rememberPromotionFolder(folder)
        }

        val migrated = coroutineScope {
            List(4) { async(Dispatchers.IO) { migrateLegacyAppSettings(target) } }.awaitAll()
        }
        val result = withSettingsStore(target) { it.defaultPromotionFolder.first() to it.promotionFolders.first() }

        assertSoftly {
            migrated shouldBe List(4) { Unit }
            result shouldBe (folder to setOf(folder))
        }
    }

    @Test
    fun `on a cancelled upgrade changed legacy settings take precedence over staging`() = withSettingsFile { file ->
        val target = appSettingsFile(file.parent.toString())
        val staged = target.resolveSibling("legacy.preferences_pb")
        val legacy = file.parent.resolve("GameTier/settings.preferences_pb")
        val folder = file.parent.resolve("Current").toString()
        withSettingsStore(staged) { it.setDefaultPromotionFolder(file.parent.resolve("Old").toString()) }
        withSettingsStore(legacy) { it.setDefaultPromotionFolder(folder) }

        val migrated = migrateLegacyAppSettings(target)
        val result = withSettingsStore(target) { it.defaultPromotionFolder.first() }

        assertSoftly {
            migrated shouldBe Unit
            result shouldBe folder
        }
    }

    @Test
    fun `on read locked legacy settings migration fails and can be retried`() = withSettingsFile { file ->
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val legacy = file.parent.resolve("GameTier/settings.preferences_pb")
        val target = appSettingsFile(file.parent.toString())
        val folder = file.parent.resolve("Games").toString()
        withSettingsStore(legacy) { it.setDefaultPromotionFolder(folder) }
        FileChannel.open(legacy, READ, NOSHARE_READ).use {
            val result = shouldThrow<IOException> { migrateLegacyAppSettings(target) }

            assertSoftly {
                result.message.isNullOrBlank() shouldBe false
                Files.exists(target) shouldBe false
            }
        }

        val migrated = migrateLegacyAppSettings(target)
        val result = withSettingsStore(target) { it.defaultPromotionFolder.first() }

        assertSoftly {
            migrated shouldBe Unit
            result shouldBe folder
        }
    }

    @Test
    fun `on a legacy directory migration fails without creating settings`() = withSettingsFile { file ->
        val legacy = file.parent.resolve("GameTier/settings.preferences_pb")
        val target = appSettingsFile(file.parent.toString())
        Files.createDirectories(legacy)

        val error = shouldThrow<IOException> { migrateLegacyAppSettings(target) }

        assertSoftly {
            error.message shouldBe "Legacy settings are not a file: $legacy"
            Files.exists(target) shouldBe false
        }
    }

    @Test
    fun `on migration the default and history survive removal of the installation directory`() = withSettingsFile { file ->
        val legacy = file.parent.resolve("GameTier/settings.preferences_pb")
        val target = appSettingsFile(file.parent.toString())
        val folder = file.parent.resolve("Games").toString()
        withSettingsStore(legacy) {
            it.setDefaultPromotionFolder(folder)
            it.rememberPromotionFolder(folder)
        }

        val migrated = migrateLegacyAppSettings(target)
        val originalRetained = Files.exists(legacy)
        Files.delete(legacy)
        val result = withSettingsStore(target) { it.defaultPromotionFolder.first() to it.promotionFolders.first() }

        assertSoftly {
            migrated shouldBe Unit
            originalRetained shouldBe true
            result shouldBe (folder to setOf(folder))
        }
    }

    @Test
    fun `on existing settings migration preserves the current preference`() = withSettingsFile { file ->
        val target = appSettingsFile(file.parent.toString())
        val legacy = file.parent.resolve("GameTier/settings.preferences_pb")
        val current = file.parent.resolve("Current").toString()
        withSettingsStore(legacy) { it.setDefaultPromotionFolder(file.parent.resolve("Old").toString()) }
        withSettingsStore(target) { it.setDefaultPromotionFolder(current) }

        val migrated = migrateLegacyAppSettings(target)
        val result = withSettingsStore(target) { it.defaultPromotionFolder.first() }

        assertSoftly {
            migrated shouldBe Unit
            result shouldBe current
        }
    }

    @Test
    fun `on missing legacy settings migration leaves storage absent`() = withSettingsFile { file ->
        val target = appSettingsFile(file.parent.toString())

        val result = migrateLegacyAppSettings(target)

        assertSoftly {
            result shouldBe Unit
            Files.exists(target.parent) shouldBe false
        }
    }

    @Test
    fun `on blocked destination migration fails and retains the legacy settings`() = withSettingsFile { file ->
        val legacy = file.parent.resolve("GameTier/settings.preferences_pb")
        withSettingsStore(legacy) { it.setDefaultPromotionFolder(file.parent.resolve("Games").toString()) }
        val original = Files.readAllBytes(legacy).toList()
        val target = appSettingsFile(file.parent.toString())
        Files.writeString(target.parent, "retained")

        val error = shouldThrow<IOException> { migrateLegacyAppSettings(target) }
        val result = Files.readAllBytes(legacy).toList()

        assertSoftly {
            error.message.isNullOrBlank() shouldBe false
            result shouldBe original
            Files.readString(target.parent) shouldBe "retained"
        }
    }
}
