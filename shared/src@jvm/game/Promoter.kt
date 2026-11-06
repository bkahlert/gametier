package com.bkahlert.gametier.game

import com.bkahlert.gametier.files.FileMover

import java.io.IOException
import java.nio.file.Path
import java.nio.file.ProviderMismatchException
import java.util.UUID

object Promoter {
    fun promote(original: Path, destination: Path) = promote(original, destination, GameFileOperations())

    internal fun promote(original: Path, destination: Path, operations: GameFileOperations, beforeMove: () -> Unit = {}) {
        var phase = "validation"
        var moved = false
        var moveAttempted = false
        var creatingProbe = false
        var originalPath = original
        var destinationPath = destination
        try {
            originalPath = canonicalGameEntry(original)
            destinationPath = canonicalGameEntry(destination)
            if (originalPath.fileSystem != destinationPath.fileSystem) {
                throw ProviderMismatchException("Original and destination must use the same filesystem provider instance")
            }
            if (gameEntryExists(destinationPath)) throw IOException("Destination already exists")
            FileMover.validateSource(originalPath)
            if (destinationPath.startsWith(originalPath.toRealPath())) {
                throw IOException("Destination must not be inside the original game")
            }

            val probe = originalPath.resolveSibling(".gametier-symlink-${UUID.randomUUID()}")
            phase = "preflight creation at '$probe'"
            creatingProbe = true
            operations.createLink(probe, originalPath)
            creatingProbe = false
            phase = "preflight cleanup at '$probe'"
            operations.delete(probe)

            phase = "recording promotion intent"
            beforeMove()
            phase = "move"
            moveAttempted = true
            operations.move(originalPath, destinationPath)
            moved = true
            phase = "link creation"
            operations.createLink(originalPath, destinationPath)
        } catch (error: Exception) {
            val failure = if (creatingProbe) SymlinkPreflightException(original, destination, phase, error)
            else transferFailure("promotion", original, destination, phase, error, moveAttempted)
            if (moved) recoverTransfer(failure) {
                if (!gameEntryExists(originalPath)) operations.move(destinationPath, originalPath)
            }
            throw failure
        }
    }
}

class SymlinkPreflightException internal constructor(original: Path, destination: Path, phase: String, cause: Exception) :
    IOException("$phase failed during promotion of '$original' with '$destination'. No game data was moved. " +
        "Symbolic-link creation must be allowed before promotion. " +
        (if (System.getProperty("os.name").startsWith("Windows")) "Windows may require administrator permission. " else "") +
        cause.message.orEmpty(), cause)
