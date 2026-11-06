package com.bkahlert.gametier.ui

import java.awt.Frame
import java.awt.FileDialog
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DnDConstants
import java.io.File
import java.io.IOException
import java.nio.file.Path
import javax.swing.JFileChooser

class DesktopSelection internal constructor(
    private val choose: (directories: Boolean, multiple: Boolean) -> List<Path>?,
) {
    constructor(owner: Frame) : this({ directories, multiple -> choosePaths(owner, directories, multiple) })

    fun files(): List<Path> = choose(false, true).orEmpty()
    fun folders(): List<Path> = choose(true, true).orEmpty()
    fun destination(): Path? = choose(true, false)?.firstOrNull()
}

fun droppedPaths(transferable: Transferable): List<Path> {
    if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
    val entries = transferable.getTransferData(DataFlavor.javaFileListFlavor)
    if (entries !is List<*> || entries.any { it !is File }) {
        throw IOException("The drop did not contain a valid file selection.")
    }
    return entries.map { (it as File).toPath() }
}

internal fun receiveFileDrop(event: DropTargetDropEvent, onSelection: (List<Path>) -> Unit): Boolean {
    if (event.sourceActions and DnDConstants.ACTION_COPY == 0) return false
    event.acceptDrop(DnDConstants.ACTION_COPY)
    val paths = droppedPaths(event.transferable)
    if (paths.isEmpty()) return false
    onSelection(paths)
    return true
}

private fun choosePaths(owner: Frame, directories: Boolean, multiple: Boolean): List<Path>? {
    if (!directories) {
        val dialog = FileDialog(owner, "Add files to GameTier", FileDialog.LOAD)
        return try {
            dialog.isMultipleMode = multiple
            dialog.isVisible = true
            dialog.files.map { it.toPath() }
        } finally {
            dialog.dispose()
        }
    }
    val chooser = JFileChooser().apply {
        dialogTitle = if (multiple) "Add folders to GameTier" else "Choose promotion folder"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isMultiSelectionEnabled = multiple
        approveButtonText = "Choose folder"
    }
    return when (chooser.showOpenDialog(owner)) {
        JFileChooser.APPROVE_OPTION -> {
            if (multiple) chooser.selectedFiles.map { it.toPath() }
            else listOfNotNull(chooser.selectedFile?.toPath())
        }
        JFileChooser.CANCEL_OPTION -> null
        else -> throw IOException("The folder picker could not open this location.")
    }
}
