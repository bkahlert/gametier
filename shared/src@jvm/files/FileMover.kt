package com.bkahlert.gametier.files

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.ReadOnlyFileSystemException
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributeView

object FileMover {
    internal fun validateSource(source: Path) {
        scan(canonicalEntry(source))
    }

    fun move(source: Path, destination: Path) {
        var phase = "validation"
        try {
            val sourcePath = canonicalEntry(source)
            val destinationPath = canonicalEntry(destination)
            if (Files.exists(destinationPath, NOFOLLOW_LINKS) || isWindowsReparsePoint(destinationPath)) {
                throw IOException("Destination already exists")
            }
            if (destinationPath.startsWith(sourcePath)) {
                throw IOException("Destination must not be inside the source")
            }
            val entries = scan(sourcePath)
            if (Files.getFileStore(sourcePath) == Files.getFileStore(destinationPath.parent)) {
                phase = "rename"
                Files.move(sourcePath, destinationPath)
                return
            }

            phase = "copy"
            entries.forEach { (relative, attributes) ->
                val from = resolve(sourcePath, relative)
                val to = resolve(destinationPath, relative)
                if (attributes.isDirectory) Files.createDirectory(to) else Files.copy(from, to)
            }
            entries.entries.toList().asReversed().forEach { (relative, _) ->
                val from = resolve(sourcePath, relative)
                val to = resolve(destinationPath, relative)
                Files.setLastModifiedTime(to, Files.getLastModifiedTime(from, NOFOLLOW_LINKS))
                copyDosAttributes(from, to)
            }

            phase = "verification"
            val current = scan(sourcePath)
            if (entries.keys != current.keys || entries.any { (path, before) ->
                    val after = current.getValue(path)
                    before.isDirectory != after.isDirectory ||
                        (!before.isDirectory && (before.size() != after.size() ||
                            before.lastModifiedTime() != after.lastModifiedTime())) ||
                        before.fileKey() != after.fileKey()
                }) {
                throw IOException("Source changed during copying")
            }
            val copied = scan(destinationPath)
            if (copied.size != entries.size || entries.any { (relative, original) ->
                    val target = copied[relativePath(destinationPath, relative)]
                    target == null || target.isDirectory != original.isDirectory ||
                        (!original.isDirectory && target.size() != original.size())
                }) {
                throw IOException("Destination entries or file sizes do not match the source")
            }

            phase = "source cleanup"
            entries.keys.toList().asReversed().forEach { deleteSource(resolve(sourcePath, it)) }
        } catch (error: IOException) {
            throw moveFailure(source, destination, phase, error)
        } catch (error: ReadOnlyFileSystemException) {
            throw moveFailure(source, destination, phase, error)
        } catch (error: UnsupportedOperationException) {
            throw moveFailure(source, destination, phase, error)
        }
    }

    private fun canonicalEntry(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        val parent = absolute.parent ?: throw IOException("A filesystem root cannot be moved")
        return parent.toRealPath().resolve(absolute.fileName)
    }

    private fun scan(root: Path): LinkedHashMap<Path, BasicFileAttributes> {
        val entries = linkedMapOf<Path, BasicFileAttributes>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            private fun record(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (isWindowsReparsePoint(path) || (!attributes.isDirectory && !attributes.isRegularFile)) {
                    throw IOException("Unsupported link or special entry: $path")
                }

                entries[root.relativize(path)] = attributes
                return FileVisitResult.CONTINUE
            }

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes) = record(dir, attrs)
            override fun visitFile(file: Path, attrs: BasicFileAttributes) = record(file, attrs)
        })
        return entries
    }

    private fun resolve(root: Path, relative: Path): Path =
        if (relative.toString().isEmpty()) root else relative.fold(root) { path, name -> path.resolve(name.toString()) }

    private fun relativePath(root: Path, relative: Path): Path = root.relativize(resolve(root, relative))

    private fun copyDosAttributes(source: Path, destination: Path) {
        val sourceView = Files.getFileAttributeView(source, DosFileAttributeView::class.java, NOFOLLOW_LINKS)
        val destinationView = Files.getFileAttributeView(destination, DosFileAttributeView::class.java, NOFOLLOW_LINKS)
        if (sourceView != null && destinationView != null) {
            val attributes = sourceView.readAttributes()
            destinationView.setArchive(attributes.isArchive)
            destinationView.setHidden(attributes.isHidden)
            destinationView.setSystem(attributes.isSystem)
            destinationView.setReadOnly(attributes.isReadOnly)
        }
    }

    private fun deleteSource(path: Path) {
        val view = Files.getFileAttributeView(path, DosFileAttributeView::class.java, NOFOLLOW_LINKS)
        val readOnly = view?.readAttributes()?.isReadOnly == true
        if (readOnly) view?.setReadOnly(false)
        try {
            Files.delete(path)
        } catch (error: IOException) {
            if (readOnly) {
                try {
                    view?.setReadOnly(true)
                } catch (restoreError: IOException) {
                    error.addSuppressed(restoreError)
                }
            }
            throw error
        }
    }

    private fun moveFailure(source: Path, destination: Path, phase: String, error: Exception): IOException {
        val state = when (phase) {
            "copy", "verification" -> "Source retained; destination may be partial."
            "source cleanup" -> "Destination copied; source cleanup is incomplete."
            else -> "Move did not complete."
        }
        return IOException("$phase failed moving '$source' to '$destination'. $state ${error.message.orEmpty()}", error)
    }
}

internal fun isWindowsReparsePoint(path: Path): Boolean {
    if (path.fileSystem != java.nio.file.FileSystems.getDefault() ||
        !System.getProperty("os.name").startsWith("Windows")) return false
    return try {
        // NIO classifies Windows junctions as directories, even with NOFOLLOW_LINKS.
        val attributes = Files.getAttribute(path, "dos:attributes", NOFOLLOW_LINKS) as? Number
            ?: throw IOException("Cannot read Windows attributes: $path")
        attributes.toInt() and 0x400 != 0
    } catch (_: java.nio.file.NoSuchFileException) {
        false
    }
}
