package com.bkahlert.gametier

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.ProviderMismatchException
import java.nio.file.attribute.BasicFileAttributes

object SymlinkCreator {
    fun create(link: Path, target: Path) {
        var phase = "validation"
        try {
            val linkPath = canonicalEntry(link)
            val targetPath = canonicalEntry(target)
            if (linkPath.fileSystem != targetPath.fileSystem) {
                throw ProviderMismatchException("Link and target must use the same filesystem provider instance")
            }
            if (Files.exists(linkPath, NOFOLLOW_LINKS) || isWindowsReparsePoint(linkPath)) {
                throw IOException("Link location already exists")
            }
            val attributes = Files.readAttributes(targetPath, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (isWindowsReparsePoint(targetPath) || (!attributes.isRegularFile && !attributes.isDirectory)) {
                throw IOException("Target must be a regular file or directory, not a link or special entry")
            }
            val resolvedTarget = targetPath.toRealPath()
            if (attributes.isDirectory && linkPath.startsWith(resolvedTarget)) {
                throw IOException("Link must not be inside its target")
            }
            phase = "creation"
            Files.createSymbolicLink(linkPath, resolvedTarget)
        } catch (error: IOException) {
            throw creationFailure(link, target, phase, error)
        } catch (error: UnsupportedOperationException) {
            throw creationFailure(link, target, phase, error)
        } catch (error: ProviderMismatchException) {
            throw creationFailure(link, target, phase, error)
        }
    }

    private fun canonicalEntry(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        val parent = absolute.parent ?: throw IOException("A filesystem root cannot be linked")
        return parent.toRealPath().resolve(absolute.fileName)
    }

    private fun creationFailure(link: Path, target: Path, phase: String, error: Exception): IOException =
        IOException("$phase failed creating symlink '$link' to '$target'. ${error.message.orEmpty()}", error)
}
