package com.bkahlert.gametier.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bkahlert.gametier.game.Promotion

@Composable
internal fun ReviewDialog(state: AppState, controller: AppController, onChooseDirectory: () -> Unit, onDetails: (String, String) -> Unit) {
    val review = state.review
    val presentation = review?.let(::reviewPresentation)
    WorkflowDialog("Add promotions", controller::cancelReview, actions = {
        CancelButton(controller::cancelReview)
        Button(onClick = controller::confirmReview, enabled = !state.preparing && !state.savingSettings && presentation?.canConfirm == true) {
            val count = presentation?.ready ?: 0
            Text("Promote $count ${if (count == 1) "item" else "items"}")
        }
    }) {
        Text("Move these items to your promotion folder. A link keeps each original location working.", style = MaterialTheme.typography.bodyMedium)
        Text("A backup stays on the original drive, using the same space as before promotion. It captures the files as they are now; later changes stay in the promoted files.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val multipleDirectories = review?.items?.mapNotNull { it.destination?.parent }?.distinct()?.let { it.size > 1 } == true
        Text(if (multipleDirectories) "Promotion folders" else "Promotion folder", style = MaterialTheme.typography.titleSmall)
        val directory = if (review != null) review.directory else state.defaultDirectory
        if (directory != null) PathText(directory)
        if (multipleDirectories) Text("Each item's original promotion folder is shown below.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onChooseDirectory, enabled = !state.savingSettings) {
                Text(if (multipleDirectories) "Change all folders…" else if (directory == null) "Choose folder…" else "Change folder…")
            }
            if (directory != null && directory == state.defaultDirectory) Text("Default", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        Text(if (state.defaultDirectory == null) "Choose your first promotion folder. It becomes the default for future additions."
            else "Applies to this addition. Your saved default stays unchanged.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.settingsError?.let { error -> Notice("Settings need attention", error.message ?: "The settings could not be read or saved.",
            { onDetails("Settings need attention", errorDetails(error)) }) }
        if (state.preparing || state.savingSettings) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if (state.savingSettings) "Saving the first promotion folder…" else "Checking your selection…", style = MaterialTheme.typography.bodySmall)
        }
        state.reviewError?.let { error -> Notice("Selection could not be checked", error.message ?: "Inspect the selection before trying again.",
            { onDetails("Selection could not be checked", buildString {
                appendLine("Selected locations:"); state.pendingSelection.forEach { appendLine(it) }
                appendLine(); append(errorDetails(error))
            }) }) }
        review?.problem?.let { Notice("Promotion folder needs attention", it) }
        HorizontalDivider()
        if (review == null) state.pendingSelection.forEach { original ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(original.fileName?.toString() ?: original.toString(), style = MaterialTheme.typography.titleSmall)
                    PathText(original)
                }
                TextButton(onClick = { controller.removeReviewItem(original) }) { Text("Remove") }
            }
        } else {
            review.items.forEach { item ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(item.original.fileName?.toString() ?: item.original.toString(), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { controller.removeReviewItem(item.original) }) { Text("Remove") }
                    }
                    PathText(item.original)
                    item.destination?.let { Text("Move to", style = MaterialTheme.typography.labelSmall); PathText(it) }
                    StatusLabel(when (item.status) { ReviewStatus.READY -> "Ready"; ReviewStatus.SKIPPED -> "Skipped"; ReviewStatus.BLOCKED -> "Blocked" },
                        when (item.status) { ReviewStatus.READY -> StatusTone.SUCCESS; ReviewStatus.SKIPPED -> StatusTone.NORMAL; ReviewStatus.BLOCKED -> StatusTone.WARNING })
                    Text(item.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider()
                }
            }
            if (review.adjustments.isNotEmpty()) {
                Text("Selection adjustments", style = MaterialTheme.typography.titleSmall)
                review.adjustments.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
            presentation?.let {
                Text("${it.ready} ready · ${it.skipped} skipped · ${it.blocked} blocked", style = MaterialTheme.typography.titleSmall)
                if (it.skipped > 0 || it.blocked > 0) Text("Only ready items will be promoted.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Checks cannot guarantee a successful transfer. Any failures remain in Activity for inspection.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun DemoteDialog(promotion: Promotion, onCancel: () -> Unit, onConfirm: () -> Unit) {
    WorkflowDialog("Demote ${promotion.original.fileName}?", onCancel, actions = {
        CancelButton(onCancel)
        Button(onClick = onConfirm) { Text("Demote") }
    }) {
        Text("Return the latest promoted files to their original location and replace the link with the file or folder.")
        Text("From", style = MaterialTheme.typography.labelMedium)
        PathText(promotion.destination)
        Text("Move back to", style = MaterialTheme.typography.labelMedium)
        PathText(promotion.original)
        if (promotion.backup != null) {
            Text("The original drive needs temporary free space for the returning files. The source backup stays safe until the return succeeds, then GameTier removes it and the promoted copy.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("The item leaves your promotions list when the move finishes. Its result stays in session Activity.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun RestoreBackupDialog(promotion: Promotion, onCancel: () -> Unit, onConfirm: () -> Unit) {
    WorkflowDialog("Restore backup of ${promotion.original.fileName}?", onCancel, actions = {
        CancelButton(onCancel)
        Button(onClick = onConfirm) { Text("Restore backup") }
    }) {
        Text("The promoted files are unavailable. Return the promotion-time snapshot to the original location.")
        Text("Changes made after promotion will be missing from this backup.")
        Text("Source backup", style = MaterialTheme.typography.labelMedium)
        promotion.backup?.let { PathText(it) }
        Text("Restore to", style = MaterialTheme.typography.labelMedium)
        PathText(promotion.original)
        Text("Restoring consumes the backup. GameTier checks the locations again before restoring.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun LinkPermissionDialog(state: AppState, controller: AppController) {
    WorkflowDialog("Allow symbolic links", controller::cancelLinkPermission, actions = {
        OutlinedButton(onClick = controller::cancelLinkPermission, enabled = !state.requestingPermission) { Text("Cancel") }
        Button(onClick = controller::confirmLinkPermission, enabled = !state.busy) { Text("Restart as administrator") }
    }) {
        Text("Windows may require administrator permission to create the link that keeps your game's original location working.")
        Text("Restart GameTier with administrator access for this session. Windows will ask you to approve. All promotions blocked by symbolic-link permissions will reopen with their destinations for review before anything moves.")
        state.linkPermission?.forEach { PathText(it.original); Text("Move to", style = MaterialTheme.typography.labelSmall); PathText(it.destination) }
        Text("No game data was moved. If you cancel the Windows prompt, GameTier stays open.", style = MaterialTheme.typography.bodySmall)
        if (state.requestingPermission) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Waiting for Windows permission and the new GameTier window…")
        }
        state.permissionError?.let { Notice("GameTier could not restart", it.message ?: "Permission was not granted. You can try again.") }
    }
}

@Composable
internal fun CloseDialog(state: AppState, onKeepOpen: () -> Unit, onCloseWhenFinished: () -> Unit) {
    WorkflowDialog("GameTier is still working", onKeepOpen, actions = {
        CancelButton(onKeepOpen, "Keep open")
        Button(onClick = onCloseWhenFinished) { Text("Close when finished") }
    }) {
        Text("GameTier needs to stay open while preparing selections, saving settings, or moving files.")
        val activity = activityPresentation(state.activity)
        if (activity.running > 0 || activity.queued > 0) Text("${activity.running} running · ${activity.queued} queued", style = MaterialTheme.typography.titleSmall)
        Text("Close when finished completes queued work and then exits.", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun DetailsDialog(title: String, details: String, onClose: () -> Unit) {
    WorkflowDialog(title, onClose, actions = { CancelButton(onClose, "Close") }) { SelectableText(details) }
}

@Composable
private fun WorkflowDialog(title: String, onDismiss: () -> Unit, actions: @Composable () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(18.dp).widthIn(max = 680.dp).fillMaxWidth().heightIn(max = 650.dp),
            shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column {
                Text(title, Modifier.padding(start = 24.dp, top = 23.dp, end = 24.dp, bottom = 12.dp), style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), content = { content() })
                HorizontalDivider(Modifier.padding(top = 12.dp))
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically, content = { actions() })
            }
        }
    }
}

@Composable
private fun CancelButton(onClick: () -> Unit, label: String = "Cancel") {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedButton(onClick = onClick, modifier = Modifier.focusRequester(focus)) { Text(label) }
}
