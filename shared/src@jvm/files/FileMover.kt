package com.bkahlert.gametier.files

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.ReadOnlyFileSystemException
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.FileTime

object FileMover {
    internal fun validateSource(source: Path) {
        scan(canonicalEntry(source))
    }

    fun move(source: Path, destination: Path) = move(source, destination) {}

    fun move(source: Path, destination: Path, onProgress: (TransferProgress) -> Unit) =
        transfer(source, destination, retainSource = false, onProgress)

    fun copy(source: Path, destination: Path, onProgress: (TransferProgress) -> Unit = {}) =
        transfer(source, destination, retainSource = true, onProgress)

    internal fun snapshot(path: Path): FileTreeSnapshot = snapshot(scan(canonicalEntry(path)))

    fun deleteTree(path: Path) = delete(path, null)

    internal fun deleteTree(path: Path, expected: FileTreeSnapshot) = delete(path, expected)

    private fun delete(path: Path, expected: FileTreeSnapshot?) {
        val absolute = path.toAbsolutePath().normalize()
        val parent = absolute.parent ?: throw IOException("A filesystem root cannot be deleted")
        var ancestor = absolute.root
        for (part in parent) {
            ancestor = ancestor.resolve(part)
            if (!Files.isDirectory(ancestor, NOFOLLOW_LINKS) || isWindowsReparsePoint(ancestor)) {
                throw IOException("Cleanup parent must be an ordinary directory: $ancestor")
            }
        }
        val root = canonicalEntry(path)
        val entries = scan(root)
        if (expected != null && snapshot(entries) != expected) throw IOException("Cleanup tree changed: $path")
        entries.keys.toList().asReversed().forEach { deleteSource(resolve(root, it)) }
    }

    private fun snapshot(entries: Map<Path, BasicFileAttributes>): FileTreeSnapshot = FileTreeSnapshot(
        entries.mapValues { (_, attributes) ->
            FileEntryState(attributes.isDirectory, attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
        },
    )

    private fun transfer(source: Path, destination: Path, retainSource: Boolean, onProgress: (TransferProgress) -> Unit) {
        var phase = "validation"
        try {
            onProgress(TransferProgress(TransferPhase.PREPARING))
            val sourcePath = canonicalEntry(source)
            val destinationPath = canonicalEntry(destination)
            if (Files.exists(destinationPath, NOFOLLOW_LINKS) || isWindowsReparsePoint(destinationPath)) {
                throw IOException("Destination already exists")
            }
            if (destinationPath.startsWith(sourcePath)) {
                throw IOException("Destination must not be inside the source")
            }
            val entries = scan(sourcePath)
            if (!retainSource && Files.getFileStore(sourcePath) == Files.getFileStore(destinationPath.parent)) {
                phase = "rename"
                onProgress(TransferProgress(TransferPhase.MOVING))
                Files.move(sourcePath, destinationPath)
                return
            }

            val nativeCopy = WindowsFileCopier.supports(sourcePath) && WindowsFileCopier.supports(destinationPath)
            val sizes = entries.filterValues { !it.isDirectory }.mapValues { (relative, attributes) ->
                if (nativeCopy) WindowsFileCopier.size(resolve(sourcePath, relative)) else attributes.size()
            }
            val total = sizes.values.fold(0L, Math::addExact)
            var transferred = 0L
            phase = "copy"
            onProgress(TransferProgress(TransferPhase.TRANSFERRING, transferred, total))
            entries.forEach { (relative, attributes) ->
                val from = resolve(sourcePath, relative)
                val to = resolve(destinationPath, relative)
                if (attributes.isDirectory) Files.createDirectory(to) else {
                    val before = transferred
                    val report: (Long) -> Unit = { bytes ->
                        transferred = before + bytes
                        onProgress(TransferProgress(TransferPhase.TRANSFERRING, transferred, total))
                    }
                    if (nativeCopy) WindowsFileCopier.copy(from, to, report) else copyWithProgress(from, to, report)
                }
            }
            onProgress(TransferProgress(TransferPhase.FINALIZING, transferred, total))
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

            if (!retainSource) {
                phase = "source cleanup"
                entries.keys.toList().asReversed().forEach { deleteSource(resolve(sourcePath, it)) }
            }
        } catch (error: IOException) {
            throw transferFailure(source, destination, phase, error, retainSource)
        } catch (error: ReadOnlyFileSystemException) {
            throw transferFailure(source, destination, phase, error, retainSource)
        } catch (error: UnsupportedOperationException) {
            throw transferFailure(source, destination, phase, error, retainSource)
        }
    }

    private fun copyWithProgress(source: Path, destination: Path, onBytes: (Long) -> Unit) {
        Files.newInputStream(source).use { input ->
            Files.newOutputStream(destination, CREATE_NEW, WRITE).use { output ->
                val buffer = ByteArray(1024 * 1024)
                var transferred = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    transferred += count
                    onBytes(transferred)
                }
            }
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

    private fun transferFailure(source: Path, destination: Path, phase: String, error: Exception, retainSource: Boolean): IOException {
        val state = when (phase) {
            "copy", "verification" -> "Source retained; destination may be partial."
            "source cleanup" -> "Destination copied; source cleanup is incomplete."
            else -> "Move did not complete."
        }
        val operation = if (retainSource) "copying" else "moving"
        return IOException("$phase failed $operation '$source' to '$destination'. $state ${error.message.orEmpty()}", error)
    }
}

internal data class FileTreeSnapshot(val entries: Map<Path, FileEntryState>)
internal data class FileEntryState(val directory: Boolean, val size: Long, val modified: FileTime, val key: Any?)

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
