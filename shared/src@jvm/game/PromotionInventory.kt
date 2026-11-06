package com.bkahlert.gametier.game

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

enum class DestinationStatus { AVAILABLE, MISSING_INDEX, INVALID_INDEX, UNAVAILABLE }
enum class PromotionCondition { PROMOTED, NEEDS_ATTENTION }

data class Promotion(
    val original: Path,
    val destination: Path,
    val state: TransferState,
    val condition: PromotionCondition,
    val problem: String? = null,
)

data class DestinationInventory(
    val directory: Path,
    val status: DestinationStatus,
    val promotions: List<Promotion> = emptyList(),
    val unrecognized: List<Path> = emptyList(),
    val error: Exception? = null,
)

object PromotionInventory {
    fun inspect(directory: Path): DestinationInventory {
        val absolute = directory.toAbsolutePath().normalize()
        val root = try { absolute.toRealPath() } catch (_: IOException) { absolute }
        val children = try {
            Files.newDirectoryStream(root).use { it.toList().sortedBy { path -> path.fileName.toString() } }
        } catch (error: Exception) {
            return DestinationInventory(root, DestinationStatus.UNAVAILABLE, error = error)
        }
        val records = try {
            PromotionIndex().read(root)
        } catch (error: NoSuchFileException) {
            return DestinationInventory(root, DestinationStatus.MISSING_INDEX, error = error)
        } catch (error: Exception) {
            return DestinationInventory(root, DestinationStatus.INVALID_INDEX, error = error)
        }
        val promotions = records.map { record ->
            val destination = root.resolve(record.name)
            val problem = if (record.state != TransferState.PROMOTED) {
                "Interrupted ${record.state.name.lowercase()} operation; inspect original and destination"
            } else relationshipProblem(record.origin, destination)
            Promotion(record.origin, destination, record.state,
                if (problem == null) PromotionCondition.PROMOTED else PromotionCondition.NEEDS_ATTENTION, problem)
        }
        val known = records.map { root.resolve(it.name) }.toSet() + setOf(root.resolve(PROMOTION_INDEX_NAME))
        return DestinationInventory(root, DestinationStatus.AVAILABLE, promotions, children.filterNot { it in known })
    }
}

internal fun relationshipProblem(original: Path, destination: Path): String? = try {
    if (!Files.isSymbolicLink(original)) throw IOException("Original is missing or is not a symbolic link")
    if (!isRealGameEntry(destination)) throw IOException("Destination is missing or is not ordinary game data")
    val target = original.parent.resolve(Files.readSymbolicLink(original)).normalize()
    if (canonicalGameEntry(target) != canonicalGameEntry(destination)) {
        throw IOException("Original link points to a different destination")
    }
    null
} catch (error: Exception) {
    error.message ?: error.javaClass.simpleName
}
