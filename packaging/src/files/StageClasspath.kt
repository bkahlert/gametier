package com.bkahlert.gametier.files

import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.CompilationArtifact
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.listDirectoryEntries

@TaskAction
fun stageInstaller(
    @Input application: CompilationArtifact,
    @Input classpath: Classpath,
    @Output outputDirectory: Path,
) = stageClasspath(application.artifact, classpath.resolvedFiles, outputDirectory)

internal fun stageClasspath(application: Path, classpath: List<Path>, outputDirectory: Path) {
    outputDirectory.createDirectories()
    outputDirectory.listDirectoryEntries().forEach { it.deleteExisting() }
    application.copyTo(outputDirectory.resolve("GameTier.jar"))
    // Toolchain 0.13's executable JAR loses dependencies with identical filenames.
    classpath.filter { it != application }.forEachIndexed { index, jar ->
        jar.copyTo(outputDirectory.resolve("$index-${jar.fileName}"))
    }
}
