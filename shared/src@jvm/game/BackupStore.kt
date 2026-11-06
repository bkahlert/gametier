package com.bkahlert.gametier.game

import com.bkahlert.gametier.files.FileMover
import com.bkahlert.gametier.files.isWindowsReparsePoint
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.DosFileAttributeView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Source records are independent of destination settings and indexes. Payloads are ordinary files. */
class BackupStore(
    private val rootFor: (Path) -> Path = { it.root.resolve(".gametier").resolve("backups") },
    private val sourceRoots: () -> Iterable<Path> = { FileSystems.getDefault().rootDirectories },
) {
    internal fun validateDestination(directory: Path) {
        val path = normalized(directory)
        val storage = normalized(rootFor(path))
        if (path.startsWith(storage)) throw IOException("Destination must not be inside source backup storage: $storage")
    }

    internal fun dataLocation(original: Path): Path = normalized(rootFor(normalized(original)))
        .resolve(original.fileName.toString()).resolve("data")

    internal fun reserve(original: Path, destination: Path): SourceBackup {
        val source = normalized(original)
        val target = normalized(destination)
        val root = normalized(rootFor(source))
        val folder = root.resolve(source.fileName.toString())
        validateLocations(source, target, folder)
        requireOrdinaryParents(root)
        if (gameEntryExists(folder)) throw IOException("Source backup already exists: $folder")
        createOrdinaryDirectories(root)
        hideDriveStorage(root)
        if (Files.getFileStore(source.parent) != Files.getFileStore(root)) {
            throw IOException("Source backup must be on the original filesystem: $root")
        }
        Files.createDirectory(folder)
        val record = SourceBackup(source, target, TransferState.PROMOTING, folder)
        Files.writeString(folder.resolve("RESTORE.txt"), "GameTier source backup\n\nOriginal: $source\nPromoted destination: $target\n\nThe ordinary file or directory named data is the original snapshot.\nTo restore manually, first verify the promoted destination is unavailable.\nRemove only the original symbolic link pointing to that destination; never replace other files.\nMove data back to the original path above. Keep this folder until recovery is complete.\n")
        write(record)
        return record
    }

    internal fun read(original: Path): SourceBackup? {
        val source = normalized(original)
        val folder = dataLocation(source).parent
        if (!gameEntryExists(folder)) return null
        return readFolder(folder).also { if (it.original != source) throw IOException("Backup belongs to another original: $folder") }
    }

    internal fun discover(): List<SourceBackup> = sourceRoots().map { normalized(rootFor(it.toAbsolutePath())) }.distinct()
        .flatMap { root ->
            try {
                requireOrdinaryParents(root)
                if (!Files.isDirectory(root, NOFOLLOW_LINKS) || isWindowsReparsePoint(root)) emptyList()
                else Files.newDirectoryStream(root).use { children ->
                    children.mapNotNull { folder -> try { readFolder(folder) } catch (_: Exception) { null } }
                }
            } catch (_: IOException) { emptyList() }
        }.sortedBy { it.original.toString() }

    internal fun write(record: SourceBackup) {
        validateLocations(record.original, record.destination, record.folder)
        requireOrdinaryParents(record.folder)
        if (!Files.isDirectory(record.folder, NOFOLLOW_LINKS)) throw IOException("Backup record directory is missing: ${record.folder}")
        val file = record.folder.resolve("backup.json")
        if (gameEntryExists(file) && !Files.isRegularFile(file, NOFOLLOW_LINKS)) throw IOException("Backup metadata must be an ordinary file: $file")
        if (isWindowsReparsePoint(file)) throw IOException("Backup metadata must not be a reparse point: $file")
        val json = buildJsonObject {
            put("version", 1)
            put("original", record.original.toString())
            put("destination", record.destination.toString())
            put("state", record.state.name)
        }
        val temporary = Files.createTempFile(record.folder, ".backup-", ".tmp")
        try {
            FileChannel.open(temporary, WRITE).use { channel ->
                val bytes = ByteBuffer.wrap(json.toString().toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (error: Exception) {
            try { Files.deleteIfExists(temporary) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw IOException("Cannot save source backup metadata '$file': ${error.message}", error)
        }
    }

    internal fun remove(record: SourceBackup) {
        requireOrdinaryParents(record.folder)
        val current = readFolder(record.folder)
        if (current.original != record.original || current.destination != record.destination) throw IOException("Source backup identity changed")
        FileMover.deleteTree(record.folder)
    }

    private fun readFolder(folder: Path): SourceBackup {
        requireOrdinaryParents(folder)
        if (!Files.isDirectory(folder, NOFOLLOW_LINKS)) throw IOException("Backup record must be a directory")
        val file = folder.resolve("backup.json")
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || isWindowsReparsePoint(file)) throw IOException("Backup metadata must be ordinary")
        val text = Files.readString(file)
        rejectDuplicateProperties(text)
        val json = Json.parseToJsonElement(text) as? JsonObject ?: throw IOException("Backup metadata must be an object")
        if (json.keys != setOf("version", "original", "destination", "state") ||
            (json["version"] as? JsonPrimitive)?.let { !it.isString && it.intOrNull == 1 } != true) throw IOException("Invalid backup metadata")
        fun string(key: String) = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IOException("Missing backup $key")
        val source = folder.fileSystem.getPath(string("original"))
        val target = folder.fileSystem.getPath(string("destination"))
        val state = try { TransferState.valueOf(string("state")) } catch (error: Exception) { throw IOException("Invalid backup state", error) }
        validateLocations(source, target, folder)
        if (normalized(rootFor(source)).resolve(source.fileName.toString()) != folder) throw IOException("Backup root does not match original")
        return SourceBackup(source, target, state, folder)
    }

    private fun validateLocations(original: Path, destination: Path, folder: Path) {
        if (!original.isAbsolute || original != original.normalize() || original.fileName == null ||
            !destination.isAbsolute || destination != destination.normalize() || destination.fileName == null ||
            folder.fileName != original.fileName) throw IOException("Invalid source backup identity")
        val storageRoots = listOf(normalized(rootFor(original)), normalized(rootFor(destination))).distinct()
        if (listOf(original, destination).any { path -> storageRoots.any { path.startsWith(it) || it.startsWith(path) } }) {
            throw IOException("Game paths must not overlap source backup storage: ${storageRoots.joinToString()}")
        }
        val paths = listOf(original, destination, folder)
        if (paths.indices.any { i -> paths.indices.any { j -> i != j && paths[i].startsWith(paths[j]) } }) {
            throw IOException("Source, destination and backup must not overlap")
        }
        if (original.fileName.toString().let { it == "." || it == ".." || it.endsWith('.') || it.endsWith(' ') || it.contains(':') }) {
            throw IOException("Invalid source backup name")
        }
    }
}

internal data class SourceBackup(val original: Path, val destination: Path, val state: TransferState, val folder: Path) {
    val data: Path get() = folder.resolve("data")
    val staging: Path get() = folder.resolve("return")
}

internal fun requireOrdinaryParents(path: Path) {
    val absolute = normalized(path)
    var current = absolute.root
    for (part in absolute) {
        current = current.resolve(part)
        if (gameEntryExists(current) && (!Files.isDirectory(current, NOFOLLOW_LINKS) || isWindowsReparsePoint(current))) {
            throw IOException("Parent must be an ordinary directory: $current")
        }
    }
}

private fun createOrdinaryDirectories(path: Path) {
    var current = path.root
    for (part in path) {
        current = current.resolve(part)
        if (!gameEntryExists(current)) Files.createDirectory(current)
        if (!Files.isDirectory(current, NOFOLLOW_LINKS) || isWindowsReparsePoint(current)) throw IOException("Backup parent must be ordinary: $current")
    }
}

private fun hideDriveStorage(backupRoot: Path) {
    val storage = backupRoot.parent?.takeIf { it.fileName?.toString()?.equals(".gametier", ignoreCase = true) == true }
        ?: return
    Files.getFileAttributeView(storage, DosFileAttributeView::class.java, NOFOLLOW_LINKS)?.setHidden(true)
}

private fun normalized(path: Path): Path = path.toAbsolutePath().normalize()
