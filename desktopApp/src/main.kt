package com.bkahlert.gametier

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.bkahlert.gametier.resources.Res
import com.bkahlert.gametier.resources.gametier
import com.bkahlert.gametier.settings.getAppSettings
import com.bkahlert.gametier.ui.App
import com.bkahlert.gametier.ui.AppController
import com.bkahlert.gametier.ui.AdministratorRestart
import com.bkahlert.gametier.ui.RestartSelection
import com.bkahlert.gametier.ui.DesktopSelection
import com.bkahlert.gametier.ui.FileSelectionPreparer
import com.bkahlert.gametier.ui.ManagerPromotionOperations
import com.bkahlert.gametier.ui.desktopFileDrop
import java.awt.Dimension
import javax.swing.UIManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

fun main(args: Array<String>) {
    val restartSelection = RestartSelection.parse(args)
    restartSelection?.localAppData?.let { System.setProperty("gametier.localAppData", it) }
    restartSelection?.classPath?.let { System.setProperty("gametier.classPath", it) }
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = getAppSettings()
    val restart = if (System.getProperty("os.name").startsWith("Windows")) AdministratorRestart() else null
    val controller = AppController(scope, settings, ManagerPromotionOperations(settings), FileSelectionPreparer(),
        restartAsAdministrator = restart?.let { { request -> it.restart(request) } })
    restartSelection?.requests?.let(controller::restorePromotions)
    try {
        application {
            val state by controller.state.collectAsState()
            LaunchedEffect(state.exitRequested) {
                if (state.exitRequested) exitApplication()
            }
            Window(
                onCloseRequest = controller::requestClose,
                title = if (restartSelection != null) "GameTier · Administrator" else "GameTier",
                icon = painterResource(Res.drawable.gametier),
                state = rememberWindowState(width = 1100.dp, height = 760.dp),
            ) {
                LaunchedEffect(Unit) {
                    if (restartSelection != null) try {
                        withContext(Dispatchers.IO) { restartSelection.acknowledge() }
                    } catch (_: Exception) {
                        exitApplication()
                    }
                }
                DisposableEffect(window) {
                    window.minimumSize = Dimension(760, 560)
                    onDispose { }
                }
                val picker = remember(window) { DesktopSelection(window) }
                var dragging by remember { mutableStateOf(false) }
                fun pick(action: () -> Unit) {
                    try { action() } catch (error: Exception) { controller.reportError(error) }
                }
                App(
                    controller = controller,
                    onChooseFiles = { pick { picker.files().takeIf { it.isNotEmpty() }?.let(controller::select) } },
                    onChooseFolders = { pick { picker.folders().takeIf { it.isNotEmpty() }?.let(controller::select) } },
                    onChooseReviewDirectory = { pick { picker.destination()?.let(controller::setReviewDirectory) } },
                    onChooseDefaultDirectory = { pick { picker.destination()?.let(controller::chooseDefault) } },
                    isDragging = dragging,
                    modifier = Modifier.desktopFileDrop(
                        enabled = !state.closeWhenFinished && !state.exitRequested &&
                            state.pendingSelection.isEmpty() && state.demotion == null && !state.closePrompt && state.linkPermission == null,
                        onDragging = { dragging = it },
                        onSelection = controller::select,
                        onError = controller::reportError,
                    ),
                )
            }
        }
    } finally {
        scope.cancel()
    }
}
