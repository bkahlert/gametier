package com.bkahlert.gametier.ui

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.nio.file.Path

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.desktopFileDrop(
    enabled: Boolean,
    onDragging: (Boolean) -> Unit,
    onSelection: (List<Path>) -> Unit,
    onError: (Throwable) -> Unit,
): Modifier {
    val dragging = rememberUpdatedState(onDragging)
    val selection = rememberUpdatedState(onSelection)
    val error = rememberUpdatedState(onError)
    val accepting = rememberUpdatedState(enabled)
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dragging.value(true) }
            override fun onExited(event: DragAndDropEvent) { dragging.value(false) }
            override fun onEnded(event: DragAndDropEvent) { dragging.value(false) }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragging.value(false)
                if (!accepting.value) return false
                return try {
                    val native = event.nativeEvent as? DropTargetDropEvent ?: return false
                    receiveFileDrop(native, selection.value)
                } catch (failure: Exception) {
                    error.value(failure)
                    false
                }
            }
        }
    }
    return dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            val native = event.nativeEvent as? DropTargetDragEvent
            enabled && native != null && native.sourceActions and DnDConstants.ACTION_COPY != 0 &&
                event.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
        },
        target = target,
    )
}
