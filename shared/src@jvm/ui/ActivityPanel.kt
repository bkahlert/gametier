package com.bkahlert.gametier.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

@Composable
internal fun ActivityPanel(state: AppState, controller: AppController, onDetails: (String, String) -> Unit, maxContentHeight: Dp = 200.dp) {
    val summary = activityPresentation(state.activity)
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { controller.setActivityExpanded(!state.activityExpanded) }) {
                    Text("${if (state.activityExpanded) "▾" else "▸"} Activity")
                }
                Text(if (summary.running + summary.queued > 0) "${summary.running} running · ${summary.queued} queued"
                    else "${summary.total} session ${if (summary.total == 1) "result" else "results"}",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (summary.unacknowledged > 0) StatusLabel("${summary.unacknowledged} need attention", StatusTone.WARNING)
                if (state.closeWhenFinished) Text("Closing when finished", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (state.activityExpanded) LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxContentHeight).padding(horizontal = 26.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("This session · Files are processed one at a time", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.activity.isEmpty()) item {
                    Text("Operations and their results will appear here until you close GameTier.", Modifier.padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(state.activity, key = { it.id }) { item ->
                    ActivityRow(item, { onDetails("${item.request.original.fileName} · operation details", activityDetails(item)) },
                        { controller.acknowledge(item.id) }, if (controller.canRequestLinkPermission(item)) ({ controller.requestLinkPermission(item.id) }) else null,
                        permissionEnabled = !state.busy && state.pendingSelection.isEmpty())
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(item: ActivityItem, onDetails: () -> Unit, onAcknowledge: () -> Unit,
    onPermission: (() -> Unit)?, permissionEnabled: Boolean) {
    val failed = item.status == ActivityStatus.FAILED || item.status == ActivityStatus.STOPPED
    val progress = activityProgressPresentation(item)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        HorizontalDivider()
        Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                StatusLabel(progress.label, when (item.status) {
                    ActivityStatus.RUNNING, ActivityStatus.QUEUED -> StatusTone.ACTIVE
                    ActivityStatus.FAILED, ActivityStatus.STOPPED -> StatusTone.WARNING
                    ActivityStatus.SUCCEEDED -> StatusTone.SUCCESS
                }, busy = item.status == ActivityStatus.RUNNING)
                Text("${item.request.kind.label} ${item.request.original.fileName}", style = MaterialTheme.typography.titleSmall)
                Text("${when (item.request.kind) {
                    TransferKind.PROMOTE -> item.request.original
                    TransferKind.DEMOTE -> item.request.destination
                    TransferKind.RESTORE_BACKUP -> item.request.backup
                }} → ${if (item.request.kind == TransferKind.PROMOTE) item.request.destination else item.request.original}",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                progress.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                progress.fraction?.let { fraction -> LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth()) }
                (item.message ?: item.error?.message)?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (onPermission != null) TextButton(onClick = onPermission, enabled = permissionEnabled) { Text("Allow symbolic links…") }
                TextButton(onClick = onDetails) { Text("Details") }
                if (failed) {
                    if (item.acknowledged) Text("Acknowledged", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else TextButton(onClick = onAcknowledge) { Text("Acknowledge") }
                }
            }
        }
    }
}
