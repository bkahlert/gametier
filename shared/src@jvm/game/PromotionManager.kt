package com.bkahlert.gametier.game

import com.bkahlert.gametier.files.FileMover
import com.bkahlert.gametier.settings.AppSettings
import com.bkahlert.gametier.settings.getAppSettings
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Application entry point for transfers tracked in each destination's `.gametier.json`.
 * [promote] takes a parent directory; the standalone [Promoter.promote] takes the full destination path.
 */
class PromotionManager internal constructor(
    private val settings: AppSettings,
    private val operations: GameFileOperations,
    private val index: PromotionIndex,
) {
    constructor(settings: AppSettings = getAppSettings()) : this(settings, GameFileOperations(), PromotionIndex())

    suspend fun registerDirectory(directory: Path) {
        serialized { register(directory) }
    }

    suspend fun inventory(): List<DestinationInventory> = serialized {
        val directories = settings.promotionFolders.first() + listOfNotNull(settings.defaultPromotionFolder.first())
        directories.map { PromotionInventory.inspect(Path.of(it)) }
            .distinctBy { it.directory }.sortedBy { it.directory.toString() }
    }

    suspend fun promote(original: Path, directory: Path): Unit = serialized {
        val origin = canonicalGameEntry(original)
        val root = register(directory)
        val target = root.resolve(origin.fileName.toString())
        val records = index.read(root)
        val pending = IndexedPromotion(target.fileName.toString(), origin, TransferState.PROMOTING)
        if (records.any { root.resolve(it.name) == target || it.origin == origin }) {
            throw IOException("Promotion is already indexed; inspect original and destination before retrying")
        }
        if (gameEntryExists(target)) throw IOException("Destination already exists: $target")
        FileMover.validateSource(origin)
        if (target.startsWith(origin.toRealPath())) throw IOException("Destination must not be inside the original game")
        Promoter.promote(origin, target, operations) {
            index.write(root, records + pending)
        }
        index.write(root, records + pending.copy(state = TransferState.PROMOTED))
    }

    suspend fun demote(directory: Path, name: String): Unit = serialized {
        val root = directory.toRealPath()
        val records = index.read(root)
        settings.rememberPromotionFolder(root.toString())
        val record = records.singleOrNull { it.name == name }
            ?: throw IOException("No indexed promotion named '$name' in '$root'")
        if (record.state != TransferState.PROMOTED) throw IOException("Promotion needs attention before demoting")
        val target = root.resolve(record.name)
        relationshipProblem(record.origin, target)?.let { throw IOException("Cannot demote '$target': $it") }
        FileMover.validateSource(target)
        val pending = records.map { if (it == record) it.copy(state = TransferState.DEMOTING) else it }
        index.write(root, pending)
        Demoter.demote(record.origin, operations)
        index.write(root, records.filterNot { it == record })
    }

    private suspend fun register(directory: Path): Path {
        val root = directory.toRealPath()
        if (!Files.isDirectory(root)) throw IOException("Destination must be a directory: $root")
        val remembered = settings.promotionFolders.first().map { Path.of(it).toAbsolutePath().normalize() }
        val known = remembered.any { path ->
            path == root || try { Files.isSameFile(path, root) } catch (_: IOException) { false }
        }
        val file = root.resolve(PROMOTION_INDEX_NAME)
        if (gameEntryExists(file)) index.read(root)
        else if (known) throw IOException("Remembered destination has lost its promotion index: $file")
        else index.write(root, emptyList())
        settings.rememberPromotionFolder(root.toString())
        return root
    }

    private suspend fun <T> serialized(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        processMutex.withLock { action() }
    }

    private companion object {
        val processMutex = Mutex()
    }
}
