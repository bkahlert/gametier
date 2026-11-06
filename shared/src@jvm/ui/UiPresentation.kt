package com.bkahlert.gametier.ui

import com.bkahlert.gametier.game.DestinationInventory
import com.bkahlert.gametier.game.DestinationStatus
import com.bkahlert.gametier.game.Promotion
import com.bkahlert.gametier.game.PromotionCondition
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap

internal enum class StatusTone { NORMAL, SUCCESS, WARNING, ACTIVE }
internal data class PromotionPresentation(val status: String, val tone: StatusTone, val canDemote: Boolean, val busy: Boolean)
internal data class DestinationNotice(val title: String, val details: String)
internal enum class PromotionsEmptyState { FIRST_USE, EMPTY, NO_MATCHES, UNAVAILABLE }
internal data class ReviewPresentation(val ready: Int, val skipped: Int, val blocked: Int, val canConfirm: Boolean)
internal data class ActivityPresentation(val running: Int, val queued: Int, val unacknowledged: Int, val total: Int)

internal fun promotionPresentation(promotion: Promotion, activity: List<ActivityItem>): PromotionPresentation {
    val operation = activity.lastOrNull {
        it.request.original == promotion.original && it.request.destination == promotion.destination &&
            (it.status == ActivityStatus.QUEUED || it.status == ActivityStatus.RUNNING)
    }
    return when {
        operation?.status == ActivityStatus.RUNNING -> PromotionPresentation(
            if (operation.request.kind == TransferKind.PROMOTE) "Promoting" else "Demoting", StatusTone.ACTIVE, false, true)
        operation?.status == ActivityStatus.QUEUED -> PromotionPresentation("Queued", StatusTone.ACTIVE, false, false)
        promotion.condition == PromotionCondition.NEEDS_ATTENTION -> PromotionPresentation("Needs attention", StatusTone.WARNING, false, false)
        else -> PromotionPresentation("Promoted", StatusTone.SUCCESS, true, false)
    }
}

internal fun destinationNotice(inventory: DestinationInventory, remembered: Set<String>): DestinationNotice? {
    val title = when (inventory.status) {
        DestinationStatus.UNAVAILABLE -> "Promotion folder unavailable"
        DestinationStatus.INVALID_INDEX -> "Promotion metadata needs attention"
        DestinationStatus.MISSING_INDEX -> if (remembered.any { it.equals(inventory.directory.toString(), ignoreCase = true) })
            "Promotion metadata is missing" else return null
        DestinationStatus.AVAILABLE -> if (inventory.unrecognized.isNotEmpty()) "Unrecognized items in promotion folder" else return null
    }
    return DestinationNotice(title, buildString {
        appendLine("Promotion folder: ${inventory.directory}")
        when (inventory.status) {
            DestinationStatus.UNAVAILABLE -> appendLine("Promotions from this folder cannot be listed. Reconnect the storage and refresh promotions.")
            DestinationStatus.INVALID_INDEX, DestinationStatus.MISSING_INDEX -> appendLine("GameTier cannot safely use this folder's metadata. Inspect the folder and metadata before making changes.")
            DestinationStatus.AVAILABLE -> {
                appendLine("These items are not indexed promotions and cannot be demoted by GameTier:")
                inventory.unrecognized.forEach { appendLine(it) }
            }
        }
        inventory.error?.let { appendLine(); append(errorDetails(it)) }
    }.trim())
}

internal fun promotionsEmptyState(directory: Path?, total: Int, search: String, hasDestinationProblems: Boolean): PromotionsEmptyState = when {
    total > 0 && search.isNotBlank() -> PromotionsEmptyState.NO_MATCHES
    hasDestinationProblems -> PromotionsEmptyState.UNAVAILABLE
    directory == null -> PromotionsEmptyState.FIRST_USE
    else -> PromotionsEmptyState.EMPTY
}

internal fun reviewPresentation(review: SelectionReview): ReviewPresentation {
    val ready = review.items.count { it.status == ReviewStatus.READY }
    return ReviewPresentation(ready, review.items.count { it.status == ReviewStatus.SKIPPED },
        review.items.count { it.status == ReviewStatus.BLOCKED }, ready > 0 && review.directory != null && review.problem == null)
}

internal fun activityPresentation(items: List<ActivityItem>): ActivityPresentation = ActivityPresentation(
    items.count { it.status == ActivityStatus.RUNNING }, items.count { it.status == ActivityStatus.QUEUED },
    items.count { (it.status == ActivityStatus.FAILED || it.status == ActivityStatus.STOPPED) && !it.acknowledged }, items.size)

internal fun activityDetails(item: ActivityItem): String = buildString {
    appendLine("Operation: ${if (item.request.kind == TransferKind.PROMOTE) "Promote" else "Demote"}")
    appendLine("Status: ${item.status.name.lowercase()}")
    appendLine("Original location: ${item.request.original}")
    appendLine("Promotion folder: ${item.request.directory}")
    appendLine("Promoted location: ${item.request.destination}")
    item.message?.let { appendLine(); appendLine(it) }
    item.error?.let { appendLine(); append(errorDetails(it)) }
}.trim()

internal fun errorDetails(error: Throwable): String = buildString {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    fun appendError(current: Throwable, label: String) {
        if (!visited.add(current)) return
        appendLine("$label${current.javaClass.simpleName}: ${current.message.orEmpty()}")
        current.cause?.let { appendError(it, "Caused by: ") }
        current.suppressed.forEach { appendError(it, "Suppressed: ") }
    }
    appendError(error, "")
}.trim()
