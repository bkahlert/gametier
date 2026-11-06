package com.bkahlert.gametier.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bkahlert.gametier.game.Promotion

@Composable
internal fun PromotionScreen(
    state: AppState,
    controller: AppController,
    onChooseFiles: () -> Unit,
    onChooseFolders: () -> Unit,
    onDetails: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var addMenu by remember { mutableStateOf(false) }
    val notices = state.inventory.mapNotNull { inventory ->
        destinationNotice(inventory, state.rememberedFolders, state.rememberedDirectoryIdentities)?.let { inventory to it }
    }
    val defaultFolder = defaultFolderPresentation(state)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxWidth < 900.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Promotions", style = MaterialTheme.typography.headlineMedium)
                    if (!compact) Text("Move games to faster storage while keeping their original locations working.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    Button(onClick = { addMenu = true }) { Text("Add promotion ▾") }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(text = { Text("Choose files…") }, onClick = { addMenu = false; onChooseFiles() })
                        DropdownMenuItem(text = { Text("Choose folders…") }, onClick = { addMenu = false; onChooseFolders() })
                    }
                }
                OutlinedButton(onClick = controller::showSettings) { Text("Settings") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Default folder", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (defaultFolder.status) {
                    DefaultFolderStatus.LOADING -> Text("Loading default folder…", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    DefaultFolderStatus.MISSING -> Text("Choose a folder before your first promotion", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    DefaultFolderStatus.CONFIGURED -> PathText(requireNotNull(defaultFolder.directory), Modifier.weight(1f), compact = true)
                }
                TextButton(onClick = controller::showSettings, enabled = defaultFolder.status != DefaultFolderStatus.LOADING) {
                    Text(if (defaultFolder.status == DefaultFolderStatus.CONFIGURED) "Change" else "Choose folder")
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = state.search, onValueChange = controller::setSearch, label = { Text("Search promotions") },
                    placeholder = { Text("Name or path") }, singleLine = true, modifier = Modifier.weight(1f))
                Text("${state.filteredPromotions.size} of ${state.promotions.size}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = controller::refresh, enabled = !state.refreshing) { Text(if (state.refreshing) "Refreshing…" else "Refresh") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.settingsError?.let { error -> item {
                    Notice("Settings need attention", error.message ?: "The settings could not be read or saved.",
                        { onDetails("Settings need attention", errorDetails(error)) })
                } }
                state.inventoryError?.let { error -> item {
                    Notice("Promotions could not be refreshed", "The last available list is still shown.",
                        { onDetails("Refresh failed", errorDetails(error)) })
                } }
                items(notices) { (inventory, notice) ->
                    Notice(notice.title, inventory.directory.toString(), { onDetails(notice.title, notice.details) })
                }
                if (state.filteredPromotions.isEmpty()) item {
                    val empty = promotionsEmptyState(state.defaultDirectory, state.promotions.size, state.search,
                        notices.any { it.first.status != com.bkahlert.gametier.game.DestinationStatus.AVAILABLE } || state.inventoryError != null)
                    EmptyPromotions(empty, onAdd = { addMenu = true }, onSettings = controller::showSettings)
                } else items(state.filteredPromotions, key = { it.destination.toString() }) { promotion ->
                    PromotionRow(promotion, state.activity, { controller.requestDemote(promotion) }, { controller.requestRestoreBackup(promotion) }, {
                        onDetails("${promotion.original.fileName} · ${promotionPresentation(promotion, state.activity).status}", promotionDetails(promotion))
                    })
                }
            }
            Text("↑  Drop files or folders anywhere here to add promotions", Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PromotionRow(promotion: Promotion, activity: List<ActivityItem>, onDemote: () -> Unit, onRestoreBackup: () -> Unit, onDetails: () -> Unit) {
    val presentation = promotionPresentation(promotion, activity)
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1.5f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(promotion.original.fileName?.toString() ?: promotion.original.toString(), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                Text("Original location", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PathText(promotion.original, compact = true)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Promotion folder", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PathText(promotion.destination.parent ?: promotion.destination, compact = true)
            }
            Column(Modifier.weight(0.9f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                StatusLabel(presentation.status, presentation.tone, presentation.busy)
                Row {
                    if (presentation.canDemote) TextButton(onClick = onDemote) { Text("Demote") }
                    if (presentation.canRestoreBackup) TextButton(onClick = onRestoreBackup) { Text("Restore backup") }
                    TextButton(onClick = onDetails) { Text("Details") }
                }
            }
        }
    }
}

@Composable
private fun EmptyPromotions(state: PromotionsEmptyState, onAdd: () -> Unit, onSettings: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(30.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(when (state) {
                PromotionsEmptyState.FIRST_USE -> "Bring your games to faster storage"
                PromotionsEmptyState.EMPTY -> "No promotions yet"
                PromotionsEmptyState.NO_MATCHES -> "No promotions match your search"
                PromotionsEmptyState.UNAVAILABLE -> "Some promotions cannot be listed"
            }, style = MaterialTheme.typography.titleLarge)
            Text(when (state) {
                PromotionsEmptyState.FIRST_USE -> "Add files or folders, then choose where to move them. GameTier keeps a link at each original location."
                PromotionsEmptyState.EMPTY -> "Add files or folders to review them before anything moves."
                PromotionsEmptyState.NO_MATCHES -> "Try another name, original location, or promotion folder."
                PromotionsEmptyState.UNAVAILABLE -> "Inspect the folder notices above and refresh after resolving the problem."
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state != PromotionsEmptyState.NO_MATCHES) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onAdd) { Text("Add promotion") }
                if (state == PromotionsEmptyState.FIRST_USE) OutlinedButton(onClick = onSettings) { Text("Choose folder") }
            }
        }
    }
}
