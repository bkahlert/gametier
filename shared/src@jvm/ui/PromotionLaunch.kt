package com.bkahlert.gametier.ui

import java.nio.file.Path

data class PromotionLaunch private constructor(val original: Path, val kind: TransferKind) {
    fun open(controller: AppController) = when (kind) {
        TransferKind.PROMOTE -> controller.select(listOf(original))
        else -> controller.openManagedSelection(original, kind)
    }

    companion object {
        fun parse(arguments: Array<String>): PromotionLaunch? {
            val kind = when (arguments.firstOrNull()) {
                "--promote" -> TransferKind.PROMOTE
                "--demote" -> TransferKind.DEMOTE
                "--restore-backup" -> TransferKind.RESTORE_BACKUP
                else -> return null
            }
            require(arguments.size == 2) { "Invalid promotion arguments" }
            val path = try { Path.of(arguments[1]) } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid promotion path", error)
            }
            require(path.isAbsolute && path.normalize().parent != null) { "Invalid promotion path: select an absolute file or folder path" }
            return PromotionLaunch(path.normalize(), kind)
        }
    }
}
