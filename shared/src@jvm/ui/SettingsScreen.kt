package com.bkahlert.gametier.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bkahlert.gametier.settings.ExplorerIntegration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsScreen(state: AppState, controller: AppController, onBrowse: () -> Unit,
    onDetails: (String, String) -> Unit, modifier: Modifier = Modifier, explorerIntegration: ExplorerIntegration? = null) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = controller::showPromotions) { Text("← Promotions") }
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        explorerIntegration?.let { ExplorerIntegrationSetting(it) }
        Text("Choose where new promotions go.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Storage", style = MaterialTheme.typography.titleMedium)
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Default promotion folder", style = MaterialTheme.typography.titleSmall)
                Text("New additions use this folder unless you choose another destination.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = state.defaultInput, onValueChange = controller::setDefaultInput, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Default promotion folder") }, singleLine = true, isError = state.settingsError != null,
                    enabled = !state.savingSettings)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onBrowse, enabled = !state.savingSettings) { Text("Browse…") }
                    Button(onClick = controller::saveDefault, enabled = !state.savingSettings) { Text(if (state.savingSettings) "Saving…" else "Save") }
                    if (!state.savingSettings && state.settingsError == null && state.defaultDirectory != null && state.defaultInput == state.defaultDirectory.toString())
                        Text("Saved", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                state.settingsError?.let { error ->
                    Text(error.message ?: "The settings could not be read or saved.", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onDetails("Settings need attention", "Entered folder: ${state.defaultInput}\n\n${errorDetails(error)}") }) {
                        Text("Error details")
                    }
                }
            }
        }
        Text("Changing the default leaves existing promotions and confirmed queued work in place.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Previously used folders", style = MaterialTheme.typography.titleMedium)
        Text("GameTier keeps listing promotions from these locations.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.rememberedFolders.isEmpty()) Text("No previously used folders yet.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.rememberedFolders.sortedBy { it.lowercase() }.forEach { folder ->
            HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectableText(folder)
                val inventory = rememberedFolderInventory(folder, state.inventory, state.rememberedDirectoryIdentities)
                if (inventory != null) {
                    val notice = destinationNotice(inventory, state.rememberedFolders, state.rememberedDirectoryIdentities)
                    if (notice != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusLabel(notice.title, StatusTone.WARNING)
                        TextButton(onClick = { onDetails(notice.title, notice.details) }) { Text("Details") }
                    } else StatusLabel("Available", StatusTone.SUCCESS)
                } else Text("Not inspected yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ExplorerIntegrationSetting(integration: ExplorerIntegration) {
    var enabled by remember(integration) { mutableStateOf(false) }
    var busy by remember(integration) { mutableStateOf(true) }
    var error by remember(integration) { mutableStateOf<Exception?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(integration) {
        try { enabled = withContext(Dispatchers.IO) { integration.isEnabled() } }
        catch (failure: Exception) { error = failure }
        finally { busy = false }
    }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Promote from File Explorer", style = MaterialTheme.typography.titleSmall)
                Switch(checked = enabled, enabled = !busy, onCheckedChange = { value ->
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { integration.setEnabled(value) }
                            enabled = value
                        } catch (failure: Exception) { error = failure }
                        finally { busy = false }
                    }
                })
            }
            Text("Show “Promote with GameTier” for eligible files and folders.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it.message ?: "Explorer integration could not be changed.", color = MaterialTheme.colorScheme.error) }
        }
    }
}
