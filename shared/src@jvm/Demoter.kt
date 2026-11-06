package com.bkahlert.gametier

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

object Demoter {
    fun demote(original: Path) = demote(original, GameFileOperations())

    internal fun demote(original: Path, operations: GameFileOperations) {
        var phase = "validation"
        var linkRemoved = false
        var originalPath = original
        var target: Path? = null
        try {
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
            operations.move(target, originalPath)
        } catch (error: Exception) {
            val failure = transferFailure("demotion", original, target, phase, error)
            if (linkRemoved) recoverTransfer(failure) {
                val remainingTarget = target
                if (!gameEntryExists(originalPath) && remainingTarget != null && isRealGameEntry(remainingTarget)) {
                    FileMover.validateSource(remainingTarget)
                    operations.createLink(originalPath, remainingTarget)
                }
            }
            throw failure
        }
    }
}
