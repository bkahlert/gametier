package com.bkahlert.gametier.ui

import com.bkahlert.gametier.game.DestinationInventory
import java.nio.file.Path

enum class ReviewStatus { READY, SKIPPED, BLOCKED }

data class ReviewItem(
    val original: Path,
    val destination: Path?,
    val status: ReviewStatus,
    val message: String,
)

data class SelectionReview(
    val directory: Path?,
    val items: List<ReviewItem>,
    val adjustments: List<String> = emptyList(),
    val problem: String? = null,
)

fun interface SelectionPreparer {
    suspend fun prepare(
        paths: List<Path>,
        directory: Path?,
        inventory: List<DestinationInventory>,
        remembered: Set<String>,
        reserved: List<Pair<Path, Path>>,
    ): SelectionReview
}
