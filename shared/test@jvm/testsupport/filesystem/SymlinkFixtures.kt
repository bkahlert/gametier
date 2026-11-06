package com.bkahlert.gametier.testsupport.filesystem

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue

internal fun requireSymlinkSupport(
    root: Path,
    required: Boolean = java.lang.Boolean.getBoolean("gametier.test.requireSymlinks"),
) {
    val failure = symlinkCreationFailure(root)
    if (required && failure != null) throw IOException("Required filesystem symlink creation unavailable", failure)
    assumeTrue(failure == null, "Filesystem symlink creation unavailable: $failure")
}

internal fun symlinkCreationFailure(root: Path): Exception? {
    val target = Files.writeString(root.resolve("symlink-probe-target"), "probe")
    val link = root.resolve("symlink-probe-link")
    try {
        Files.createSymbolicLink(link, target)
        return null
    } catch (error: IOException) {
        return error
    } catch (error: UnsupportedOperationException) {
        return error
    } finally {
        Files.deleteIfExists(link)
        Files.delete(target)
    }
}
