package com.bkahlert.gametier.ui

import com.bkahlert.gametier.files.TransferPhase
import java.util.Locale

internal data class ActivityProgressPresentation(val label: String, val detail: String? = null, val fraction: Float? = null)

internal fun activityProgressPresentation(item: ActivityItem): ActivityProgressPresentation {
    if (item.status != ActivityStatus.RUNNING) return ActivityProgressPresentation(when (item.status) {
        ActivityStatus.QUEUED -> "Queued"
        ActivityStatus.SUCCEEDED -> "Completed"
        ActivityStatus.FAILED -> "Failed"
        ActivityStatus.STOPPED -> "Stopped"
        ActivityStatus.RUNNING -> error("Running activity is handled below")
    })
    val progress = item.progress ?: return ActivityProgressPresentation("Preparing")
    val stage = when (progress.phase) {
        TransferPhase.PREPARING -> "Preparing"
        TransferPhase.MOVING -> "Moving"
        TransferPhase.TRANSFERRING -> "Transferring"
        TransferPhase.FINALIZING -> "Finalizing"
    }
    val label = if (progress.recovering) "Recovering · $stage" else stage
    if (progress.phase != TransferPhase.TRANSFERRING || progress.totalBytes <= 0) return ActivityProgressPresentation(label)
    val fraction = (progress.transferredBytes.toDouble() / progress.totalBytes).coerceIn(0.0, 1.0)
    val percent = progress.transferredBytes.coerceIn(0, progress.totalBytes).toBigInteger() * 100.toBigInteger() /
        progress.totalBytes.toBigInteger()
    return ActivityProgressPresentation(label,
        "${formatBytes(progress.transferredBytes)} / ${formatBytes(progress.totalBytes)} · $percent%",
        fraction.toFloat())
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}
