package com.bkahlert.gametier.game

import com.bkahlert.gametier.files.isWindowsReparsePoint
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

object PromotionEligibility {
    fun check(path: Path): String? = try {
        val absolute = path.toAbsolutePath().normalize()
        if (absolute.parent == null) throw IOException("A filesystem root cannot be promoted")
        val attributes = Files.readAttributes(absolute, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if ((!attributes.isDirectory && !attributes.isRegularFile) || isWindowsReparsePoint(absolute)) {
            throw IOException("Unsupported link or special entry: $absolute")
        }
        val canonical = absolute.toRealPath()
        val directories = if (attributes.isDirectory) listOf(absolute, canonical)
        else listOf(absolute.parent, canonical.parent)
        val inspected = mutableSetOf<Path>()
        directories.forEach { first ->
            var directory: Path? = first
            while (directory != null && inspected.add(directory)) {
                if (!Files.readAttributes(directory, BasicFileAttributes::class.java).isDirectory) {
                    throw IOException("Source ancestor must be a directory: $directory")
                }
                if (directory.fileName?.toString()?.equals(".gametier", ignoreCase = true) == true) {
                    throw IOException("Source is inside GameTier backup storage: $directory")
                }
                val marker = directory.resolve(PROMOTION_INDEX_NAME)
                val present = try {
                    Files.readAttributes(marker, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    true
                } catch (_: NoSuchFileException) {
                    false
                }
                if (present) throw IOException("Source is inside a managed promotion directory; promotion index exists: $marker")
                directory = directory.parent
            }
        }
        null
    } catch (error: Exception) {
        "Cannot inspect promotion source '$path': ${error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName}"
    }
}
