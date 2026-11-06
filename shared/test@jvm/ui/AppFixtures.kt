package com.bkahlert.gametier.ui

import com.bkahlert.gametier.game.DestinationInventory
import com.bkahlert.gametier.files.TransferProgress
import com.bkahlert.gametier.settings.AppSettings
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal fun appPath(name: String): Path = Path.of(name).toAbsolutePath().normalize()

internal class AppTestSettings(default: Path? = appPath("destination")) : AppSettings {
    val defaults = MutableStateFlow(default?.toString())
    val remembered = MutableStateFlow(emptySet<String>())
    override var defaultPromotionFolder: Flow<String?> = defaults
    override var promotionFolders: Flow<Set<String>> = remembered
    var saveError: Throwable? = null
    var saveGate: CompletableDeferred<Unit>? = null
    val saved = mutableListOf<String>()
    override suspend fun setDefaultPromotionFolder(path: String) {
        saveGate?.await()
        saveError?.let { throw it }
        saved += path
        defaults.value = path
    }
    override suspend fun clearDefaultPromotionFolder() { defaults.value = null }
    override suspend fun rememberPromotionFolder(path: String) { remembered.value += path }
}

internal class AppTestOperations : PromotionOperations {
    var snapshot = emptyList<DestinationInventory>()
    var inventoryError: Throwable? = null
    var inventoryGate: CompletableDeferred<Unit>? = null
    var transfer: suspend (TransferRequest) -> Unit = {}
    var restoreProgress: TransferProgress? = null
    var health: suspend (Path) -> String? = { null }
    val started = mutableListOf<TransferRequest>()
    var inventoryCalls = 0
    override suspend fun inventory(): List<DestinationInventory> {
        inventoryCalls++
        inventoryGate?.await()
        inventoryError?.let { throw it }
        return snapshot
    }
    override suspend fun promote(original: Path, directory: Path) {
        val request = TransferRequest(TransferKind.PROMOTE, original, directory, directory.resolve(original.fileName))
        started += request
        transfer(request)
    }
    override suspend fun demote(directory: Path, name: String) {
        val promotion = snapshot.flatMap { it.promotions }.single { it.destination == directory.resolve(name) }
        val request = TransferRequest(TransferKind.DEMOTE, promotion.original, directory, promotion.destination)
        started += request
        transfer(request)
        snapshot = snapshot.map { it.copy(promotions = it.promotions - promotion) }
    }
    override suspend fun restoreBackup(original: Path, onProgress: (TransferProgress) -> Unit) {
        val promotion = snapshot.flatMap { it.promotions }.single { it.original == original }
        val request = TransferRequest(TransferKind.RESTORE_BACKUP, original, promotion.destination.parent, promotion.destination, promotion.backup)
        started += request
        restoreProgress?.let(onProgress)
        transfer(request)
        snapshot = snapshot.map { it.copy(promotions = it.promotions - promotion) }
    }
    override suspend fun destinationProblem(directory: Path): String? = health(directory)
}

internal val readyPreparer = SelectionPreparer { paths, directory, _, _, _ ->
    SelectionReview(directory, paths.map {
        ReviewItem(it, directory?.resolve(it.fileName), if (directory == null) ReviewStatus.BLOCKED else ReviewStatus.READY, "")
    })
}
