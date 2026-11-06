package com.bkahlert.gametier.ui

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.Point
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import org.junit.jupiter.api.Nested

class DesktopSelectionTest {
    @Nested
    inner class Pickers {
        @Test
        fun `on cancel no selection is delivered`() {
            val picker = DesktopSelection { _, _ -> null }

            val files = picker.files()
            val folders = picker.folders()
            val destination = picker.destination()

            assertSoftly {
                files shouldBe emptyList()
                folders shouldBe emptyList()
                destination shouldBe null
            }
        }

        @Test
        fun `on selection picker intent and multiplicity are retained`() {
            val requests = mutableListOf<Pair<Boolean, Boolean>>()
            val selected = listOf(Path.of("C:/Games/one"), Path.of("C:/Games/two"))
            val picker = DesktopSelection { directories, multiple ->
                requests += directories to multiple
                if (multiple) selected else selected.take(1)
            }

            val files = picker.files()
            val folders = picker.folders()
            val destination = picker.destination()

            assertSoftly {
                requests shouldBe listOf(false to true, true to true, true to false)
                files shouldBe selected
                folders shouldBe selected
                destination shouldBe selected.first()
            }
        }

        @Test
        fun `on picker error the caller receives the failure`() {
            val failure = IOException("The picker cannot access this folder")
            val picker = DesktopSelection { _, _ -> throw failure }

            val result = shouldThrow<IOException> { picker.files() }

            result shouldBe failure
        }
    }

    @Nested
    inner class Drops {
        @Test
        fun `on a move gesture review negotiates copy before receiving paths`() {
            val event = TestDrop(DnDConstants.ACTION_COPY_OR_MOVE)
            val received = mutableListOf<Path>()

            val result = receiveFileDrop(event) {
                event.acceptedAction shouldBe DnDConstants.ACTION_COPY
                received += it
            }

            assertSoftly {
                result shouldBe true
                event.acceptedAction shouldBe DnDConstants.ACTION_COPY
                received shouldBe listOf(Path.of("C:/Games/one"))
            }
        }

        @Test
        fun `on a source allowing only move no review or successful drop is reported`() {
            val event = TestDrop(DnDConstants.ACTION_MOVE)
            val received = mutableListOf<Path>()

            val result = receiveFileDrop(event) { received += it }

            assertSoftly {
                result shouldBe false
                event.acceptedAction shouldBe null
                received shouldBe emptyList()
            }
        }

        @Test
        fun `on files and folders native paths preserve spaces and unicode`() {
            val files = listOf(File("C:/Games/A game"), File("C:/Roms/日本語 #1.rom"))

            val result = droppedPaths(FileTransfer(files))

            result shouldBe files.map { it.toPath() }
        }

        @Test
        fun `on nonfile data the drop is ignored`() {
            val result = droppedPaths(StringSelection("C:/Games/one"))

            result shouldBe emptyList()
        }

        @Test
        fun `on malformed native data the complete drop is rejected`() {
            val result = shouldThrow<IOException> { droppedPaths(FileTransfer(listOf(File("C:/one"), "bad"))) }

            result.message shouldBe "The drop did not contain a valid file selection."
        }

        @Test
        fun `on native read failure the cause remains inspectable`() {
            val failure = IOException("Native data unavailable")
            val transfer = object : Transferable {
                override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
                override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
                override fun getTransferData(flavor: DataFlavor): Any = throw failure
            }

            val result = shouldThrow<IOException> { droppedPaths(transfer) }

            result shouldBe failure
        }
    }
}

private class FileTransfer(private val files: List<Any>) : Transferable {
    override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: DataFlavor): Any = files
}

private class TestDrop(sourceActions: Int) : DropTargetDropEvent(
    DropTarget().dropTargetContext, Point(0, 0), DnDConstants.ACTION_MOVE, sourceActions,
) {
    var acceptedAction: Int? = null
    override fun acceptDrop(action: Int) { acceptedAction = action }
    override fun getTransferable(): Transferable = FileTransfer(listOf(File("C:/Games/one")))
}
