package com.bkahlert.gametier.game

import com.bkahlert.gametier.files.FileMover
import com.bkahlert.gametier.files.TransferProgress
import com.bkahlert.gametier.files.TransferPhase

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

object Demoter {
    fun demote(original: Path) = demote(original, GameFileOperations())

    fun demote(original: Path, onProgress: (TransferProgress) -> Unit) = demote(original, GameFileOperations(), onProgress)

    internal fun demote(original: Path, operations: GameFileOperations, onProgress: (TransferProgress) -> Unit = {}) {
        var phase = "validation"
        var linkRemoved = false
        var originalPath = original
        var target: Path? = null
        var progress = TransferProgress(TransferPhase.PREPARING)
        try {
            onProgress(TransferProgress(TransferPhase.PREPARING))
            originalPath = canonicalGameEntry(original)
            if (!Files.isSymbolicLink(originalPath)) throw IOException("Original must be a symbolic link")
            target = originalPath.parent.resolve(Files.readSymbolicLink(originalPath)).normalize()
            target = canonicalGameEntry(target)
            FileMover.validateSource(target)
            if (originalPath.startsWith(target.toRealPath())) {
                throw IOException("Original link must not be inside its target")
            }

            phase = "link removal"
            operations.delete(originalPath)
            linkRemoved = true
            phase = "move"
            operations.move(target, originalPath) {
                progress = it
                onProgress(it)
            }
            onProgress(progress.copy(phase = TransferPhase.FINALIZING))
        } catch (error: Exception) {
            val failure = transferFailure("demotion", original, target, phase, error)
            if (linkRemoved) recoverTransfer(failure) {
                val remainingTarget = target
                if (!gameEntryExists(originalPath) && remainingTarget != null && isRealGameEntry(remainingTarget)) {
                    val reportRecovery = recoveryProgress(failure, onProgress)
                    reportRecovery(TransferProgress(TransferPhase.PREPARING))
                    FileMover.validateSource(remainingTarget)
                    reportRecovery(TransferProgress(TransferPhase.FINALIZING))
                    operations.createLink(originalPath, remainingTarget)
                }
            }
            throw failure
        }
    }
}
