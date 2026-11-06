package com.bkahlert.gametier.settings

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.runBlocking

internal fun migrateLegacyAppSettings(target: Path) {
    if (Files.exists(target)) return
    val source = listOf(target.parent.parent.resolve("GameTier/settings.preferences_pb"),
        target.resolveSibling("legacy.preferences_pb")).firstOrNull { source ->
        try {
            val attributes = Files.readAttributes(source, BasicFileAttributes::class.java)
            if (!attributes.isRegularFile) throw IOException("Legacy settings are not a file: $source")
            true
        } catch (_: NoSuchFileException) { false }
    } ?: return
    runBlocking {
        SettingsCoordinator(target.toFile().canonicalFile.toPath()).lock {
            if (!Files.exists(target)) {
                val temporary = Files.createTempFile(target.parent, "settings-migration-", ".tmp")
                try {
                    Files.copy(source, temporary, REPLACE_EXISTING)
                    Files.move(temporary, target, ATOMIC_MOVE)
                } finally { Files.deleteIfExists(temporary) }
            }
        }
    }
}
