package com.bkahlert.gametier.ui

import com.bkahlert.gametier.game.DestinationInventory
import com.bkahlert.gametier.game.DestinationStatus
import com.bkahlert.gametier.game.PromotionInventory
import com.bkahlert.gametier.game.PromotionManager
import com.bkahlert.gametier.files.TransferProgress
import com.bkahlert.gametier.settings.AppSettings
import com.bkahlert.gametier.settings.getAppSettings
import java.nio.file.Path
import java.nio.file.Files
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

interface PromotionOperations {
    suspend fun inventory(): List<DestinationInventory>
    suspend fun promote(original: Path, directory: Path)
    suspend fun demote(directory: Path, name: String)
    suspend fun restoreBackup(original: Path, onProgress: (TransferProgress) -> Unit = {})
    suspend fun promote(original: Path, directory: Path, onProgress: (TransferProgress) -> Unit) = promote(original, directory)
    suspend fun demote(directory: Path, name: String, onProgress: (TransferProgress) -> Unit) = demote(directory, name)
    suspend fun destinationProblem(directory: Path): String?
}

class ManagerPromotionOperations(
    private val settings: AppSettings = getAppSettings(),
    private val manager: PromotionManager = PromotionManager(settings),
) : PromotionOperations {
    override suspend fun inventory(): List<DestinationInventory> = manager.inventory()
    override suspend fun promote(original: Path, directory: Path) = manager.promote(original, directory)
    override suspend fun demote(directory: Path, name: String) = manager.demote(directory, name)
    override suspend fun promote(original: Path, directory: Path, onProgress: (TransferProgress) -> Unit) = manager.promote(original, directory, onProgress)
    override suspend fun demote(directory: Path, name: String, onProgress: (TransferProgress) -> Unit) = manager.demote(directory, name, onProgress)
    override suspend fun restoreBackup(original: Path, onProgress: (TransferProgress) -> Unit) = manager.restoreBackup(original, onProgress)
    override suspend fun destinationProblem(directory: Path): String? = withContext(Dispatchers.IO) {
        val inventory = PromotionInventory.inspect(directory)
        when (inventory.status) {
            DestinationStatus.UNAVAILABLE -> "Destination is unavailable: ${inventory.directory}"
            DestinationStatus.INVALID_INDEX -> "Destination metadata is invalid: ${inventory.directory}"
            DestinationStatus.MISSING_INDEX -> {
                val remembered = settings.promotionFolders.first().any { path ->
                    val known = Path.of(path)
                    known.toAbsolutePath().normalize() == inventory.directory ||
                        try { Files.isSameFile(known, inventory.directory) } catch (_: IOException) { false }
                }
                when {
                    remembered -> "Remembered destination has lost its promotion index: ${inventory.directory}"
                    !Files.isWritable(inventory.directory) -> "Destination is not writable: ${inventory.directory}"
                    else -> null
                }
            }
            DestinationStatus.AVAILABLE -> when {
                !Files.isWritable(inventory.directory) -> "Destination is not writable: ${inventory.directory}"
                !Files.isWritable(inventory.directory.resolve(".gametier.json")) -> "Destination metadata is not writable: ${inventory.directory}"
                else -> null
            }
        }
    }
}
