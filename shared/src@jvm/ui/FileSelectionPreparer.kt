package com.bkahlert.gametier.ui

import com.bkahlert.gametier.files.FileMover
import com.bkahlert.gametier.game.DestinationInventory
import com.bkahlert.gametier.game.DestinationStatus
import com.bkahlert.gametier.game.PROMOTION_INDEX_NAME
import com.bkahlert.gametier.game.PromotionInventory
import com.bkahlert.gametier.game.canonicalGameEntry
import com.bkahlert.gametier.game.gameEntryExists
import com.bkahlert.gametier.game.isRealGameEntry
import com.bkahlert.gametier.settings.validatePromotionFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FileSelectionPreparer : SelectionPreparer {
    override suspend fun prepare(
        paths: List<Path>,
        directory: Path?,
        inventory: List<DestinationInventory>,
        remembered: Set<String>,
        reserved: List<Pair<Path, Path>>,
    ): SelectionReview = withContext(Dispatchers.IO) {
        val adjustments = mutableListOf<String>()
        val unique = linkedMapOf<Path, Selection>()
        paths.forEach { path ->
            val selection = selection(path)
            if (unique.putIfAbsent(selection.original, selection) != null) {
                adjustments += "Removed duplicate selection: $path"
            }
        }
        val selections = unique.values.filter { selection ->
            val parent = unique.values.firstOrNull {
                it.directory && it.original != selection.original && selection.original.startsWith(it.original)
            }
            if (parent != null) adjustments += "Included ${selection.original} with selected folder ${parent.original}"
            parent == null
        }
        val destination = inspectDestination(directory, remembered)
        val promotions = (inventory.filterNot { directoryIdentity(it.directory) == destination.directory } +
            listOfNotNull(destination.inventory)).flatMap { it.promotions }
        val indexed = promotions.map { entryIdentityOrAbsolute(it.original) to entryIdentityOrAbsolute(it.destination) }
        val pending = reserved.map { entryIdentityOrAbsolute(it.first) to entryIdentityOrAbsolute(it.second) }
        val targets = selections.map { target(it.original, destination.directory) }
        val prepared = selections.mapIndexed { index, selection ->
            val original = selection.original
            val target = targets[index]
            fun item(status: ReviewStatus, message: String) = ReviewItem(original, target, status, message)
            when {
                indexed.any { (origin, data) -> original == origin || original.startsWith(data) } ->
                    item(ReviewStatus.SKIPPED, "Already indexed; inspect the existing promotion for its condition")
                pending.any { (origin, data) -> original.startsWith(origin) || original.startsWith(data) } ->
                    item(ReviewStatus.SKIPPED, "Already queued or included in queued work")
                selection.directory && indexed.any { (origin, data) -> origin.startsWith(original) || data.startsWith(original) } ->
                    item(ReviewStatus.BLOCKED, "Selected folder contains an indexed promotion")
                selection.directory && pending.any { (origin, data) -> origin.startsWith(original) || data.startsWith(original) } ->
                    item(ReviewStatus.BLOCKED, "Selected folder contains queued work")
                selection.problem != null -> item(ReviewStatus.BLOCKED, selection.problem)
                destination.problem != null -> item(ReviewStatus.BLOCKED, destination.problem)
                else -> try {
                    validateName(original.fileName.toString(), destination.directory!!)
                    FileMover.validateSource(original)
                    if (target!!.startsWith(original)) throw IOException("Destination must not be inside the original game")
                    if (gameEntryExists(target)) throw IOException("Destination already exists: $target")
                    if (pending.any { it.second == target }) throw IOException("Destination is reserved by queued work: $target")
                    if (indexed.any { it.second == target }) throw IOException("Destination is already indexed: $target")
                    item(ReviewStatus.READY, "Ready to promote")
                } catch (error: Exception) {
                    item(ReviewStatus.BLOCKED, error.description())
                }
            }
        }
        val duplicateTargets = prepared.filter { it.status == ReviewStatus.READY }.groupingBy { it.destination }
            .eachCount().filterValues { it > 1 }.keys
        val items = prepared.map { item ->
            if (item.status == ReviewStatus.READY && item.destination in duplicateTargets) {
                item.copy(status = ReviewStatus.BLOCKED, message = "Selections have the same destination: ${item.destination}")
            } else item
        }
        SelectionReview(destination.directory, items, adjustments, destination.problem)
    }
}

private data class Selection(val original: Path, val directory: Boolean, val problem: String? = null)

private fun selection(path: Path): Selection = try {
    val original = entryIdentity(path)
    Selection(original, isRealGameEntry(original) && Files.isDirectory(original, NOFOLLOW_LINKS))
} catch (error: Exception) {
    Selection(path.toAbsolutePath().normalize(), false, error.description())
}

private data class ReviewedDestination(
    val directory: Path?,
    val inventory: DestinationInventory? = null,
    val problem: String? = null,
)

private fun inspectDestination(directory: Path?, remembered: Set<String>): ReviewedDestination {
    if (directory == null) return ReviewedDestination(null, problem = "Choose a promotion folder")
    var root = directory
    return try {
        validatePromotionFolder(directory.toString())
        root = directory.toAbsolutePath().normalize()
        root = root.toRealPath()
        if (!Files.isDirectory(root)) throw IOException("Destination must be a directory: $root")
        if (!Files.isWritable(root)) throw IOException("Destination is not writable: $root")
        val inspected = PromotionInventory.inspect(root)
        val problem = when (inspected.status) {
            DestinationStatus.AVAILABLE -> null
            DestinationStatus.MISSING_INDEX -> if (remembered.any { path ->
                try { directoryIdentity(directory.fileSystem.getPath(path)) == root } catch (_: Exception) { false }
            }) "Remembered destination has lost its promotion index: ${root.resolve(PROMOTION_INDEX_NAME)}" else null
            DestinationStatus.INVALID_INDEX, DestinationStatus.UNAVAILABLE ->
                inspected.error?.description() ?: "Cannot inspect destination: $root"
        }
        ReviewedDestination(root, inspected, problem)
    } catch (error: Exception) {
        ReviewedDestination(root, problem = error.description())
    }
}

private fun entryIdentity(path: Path): Path {
    val absolute = path.toAbsolutePath().normalize()
    val entry = try {
        canonicalGameEntry(absolute)
    } catch (_: NoSuchFileException) {
        parentIdentity(absolute.parent!!).resolve(absolute.fileName.toString())
    }
    return if (isRealGameEntry(entry)) entry.toRealPath() else entry
}

private fun parentIdentity(path: Path): Path = try {
    path.toRealPath()
} catch (error: NoSuchFileException) {
    val parent = path.parent ?: throw error
    parentIdentity(parent).resolve(path.fileName.toString())
}

private fun entryIdentityOrAbsolute(path: Path): Path = try {
    entryIdentity(path)
} catch (_: Exception) {
    path.toAbsolutePath().normalize()
}

private fun directoryIdentity(path: Path): Path = try {
    path.toRealPath()
} catch (_: Exception) {
    path.toAbsolutePath().normalize()
}

private fun target(original: Path, directory: Path?): Path? = try {
    original.fileName?.let { directory?.resolve(it.toString()) }
} catch (_: Exception) {
    null
}

private fun validateName(name: String, directory: Path) {
    if (name.isBlank() || name == "." || name == ".." || name.endsWith('.') || name.endsWith(' ') ||
        name.contains(':') || name.contains('/') || name.contains('\\')) {
        throw IOException("Source name cannot be used as a promotion destination: $name")
    }
    if (directory.fileSystem.getPath(name) == directory.fileSystem.getPath(PROMOTION_INDEX_NAME)) {
        throw IOException("Source name is reserved for the promotion index: $name")
    }
}

private fun Exception.description(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
