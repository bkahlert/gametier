package com.bkahlert.gametier.game

import com.bkahlert.gametier.files.FileMover
import com.bkahlert.gametier.files.SymlinkCreator
import com.bkahlert.gametier.files.isWindowsReparsePoint

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal class GameFileOperations(
    val move: (Path, Path) -> Unit = FileMover::move,
    val createLink: (Path, Path) -> Unit = SymlinkCreator::create,
    val delete: (Path) -> Unit = { Files.delete(it) },
)

internal fun canonicalGameEntry(path: Path): Path {
    val absolute = path.toAbsolutePath().normalize()
    val parent = absolute.parent ?: throw IOException("A filesystem root cannot be transferred")
    return parent.toRealPath().resolve(absolute.fileName)
}

internal fun gameEntryExists(path: Path): Boolean =
    Files.exists(path, NOFOLLOW_LINKS) || isWindowsReparsePoint(path)

internal fun isRealGameEntry(path: Path): Boolean =
    (Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isDirectory(path, NOFOLLOW_LINKS)) &&
        !isWindowsReparsePoint(path)

internal fun transferFailure(
    operation: String,
    original: Path,
    target: Path?,
    phase: String,
    error: Exception,
): IOException = IOException(
    "$phase failed during $operation of '$original'" +
        (target?.let { " with '$it'" } ?: "") +
        ". Inspect these locations before retrying; recovery may be incomplete. ${error.message.orEmpty()}",
    error,
)

internal inline fun recoverTransfer(failure: IOException, recovery: () -> Unit) {
    try {
        recovery()
    } catch (recoveryFailure: Exception) {
        failure.addSuppressed(recoveryFailure)
    }
}
