package com.bkahlert.gametier.ui

import com.bkahlert.gametier.game.DestinationInventory
import com.bkahlert.gametier.game.Promotion
import java.nio.file.Path

enum class AppScreen { PROMOTIONS, SETTINGS }
enum class TransferKind { PROMOTE, DEMOTE }
enum class ActivityStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, STOPPED }

data class TransferRequest(
    val kind: TransferKind,
    val original: Path,
    val directory: Path,
    val destination: Path,
)

data class ActivityItem(
    val id: Long,
    val request: TransferRequest,
    val status: ActivityStatus,
    val error: Throwable? = null,
    val message: String? = null,
    val acknowledged: Boolean = false,
)

data class AppState(
    val screen: AppScreen = AppScreen.PROMOTIONS,
    val defaultDirectory: Path? = null,
    val defaultInput: String = "",
    val settingsError: Throwable? = null,
    val rememberedFolders: Set<String> = emptySet(),
    val rememberedDirectoryIdentities: Map<String, Path> = emptyMap(),
    val inventory: List<DestinationInventory> = emptyList(),
    val inventoryError: Throwable? = null,
    val inventoryLoaded: Boolean = false,
    val refreshing: Boolean = false,
    val pendingSelection: List<Path> = emptyList(),
    val review: SelectionReview? = null,
    val preparing: Boolean = false,
    val reviewError: Throwable? = null,
    val demotion: Promotion? = null,
    val activity: List<ActivityItem> = emptyList(),
    val activityExpanded: Boolean = false,
    val search: String = "",
    val closePrompt: Boolean = false,
    val closeWhenFinished: Boolean = false,
    val exitRequested: Boolean = false,
    val savingSettings: Boolean = false,
    val inputError: Throwable? = null,
    val settingsLoading: Boolean = false,
) {
    val busy: Boolean get() = settingsLoading || preparing || savingSettings || refreshing ||
        activity.any { it.status == ActivityStatus.QUEUED || it.status == ActivityStatus.RUNNING }
    val promotions: List<Promotion> get() = inventory.flatMap { it.promotions }
    val filteredPromotions: List<Promotion> get() {
        val query = search.trim()
        return if (query.isEmpty()) promotions else promotions.filter {
            it.original.toString().contains(query, ignoreCase = true) ||
                it.destination.toString().contains(query, ignoreCase = true)
        }
    }
    val readyCount: Int get() = review?.items?.count { it.status == ReviewStatus.READY } ?: 0
}
