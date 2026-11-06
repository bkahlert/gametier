package com.bkahlert.gametier.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun App(
    controller: AppController,
    onChooseFiles: () -> Unit,
    onChooseFolders: () -> Unit,
    onChooseReviewDirectory: () -> Unit,
    onChooseDefaultDirectory: () -> Unit,
    modifier: Modifier = Modifier,
    isDragging: Boolean = false,
) {
    val state by controller.state.collectAsState()
    var details by remember { mutableStateOf<Pair<String, String>?>(null) }
    val showDetails: (String, String) -> Unit = { title, body -> details = title to body }
    GameTierTheme {
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val activityHeight = (maxHeight * 0.25f).coerceAtMost(200.dp)
                Column(Modifier.fillMaxSize()) {
                    when (state.screen) {
                        AppScreen.PROMOTIONS -> PromotionScreen(state, controller, onChooseFiles, onChooseFolders, showDetails, Modifier.weight(1f))
                        AppScreen.SETTINGS -> SettingsScreen(state, controller, onChooseDefaultDirectory, showDetails, Modifier.weight(1f))
                    }
                    ActivityPanel(state, controller, showDetails, maxContentHeight = activityHeight)
                }
                if (isDragging) Surface(Modifier.fillMaxSize().padding(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.97f),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
                        Text("↑", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                        Text("Drop to add promotions", style = MaterialTheme.typography.headlineSmall)
                        Text("Files and folders are welcome.")
                        state.defaultDirectory?.let { PathText(it) }
                        Text("Review your selection before anything moves.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (state.pendingSelection.isNotEmpty() || state.review != null || state.preparing || state.reviewError != null)
            ReviewDialog(state, controller, onChooseReviewDirectory, showDetails)
        state.demotion?.let { DemoteDialog(it, controller::cancelDemote, controller::confirmDemote) }
        state.backupRestoration?.let { RestoreBackupDialog(it, controller::cancelRestoreBackup, controller::confirmRestoreBackup) }
        state.linkPermission?.let { LinkPermissionDialog(state, controller) }
        if (state.closePrompt) CloseDialog(state, controller::keepOpen, controller::closeWhenFinished)
        state.inputError?.let { DetailsDialog("Selection could not be opened", errorDetails(it), controller::clearError) }
        details?.let { (title, body) -> DetailsDialog(title, body) { details = null } }
    }
}
