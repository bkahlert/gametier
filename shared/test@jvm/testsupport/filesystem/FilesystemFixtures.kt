package com.bkahlert.gametier.testsupport.filesystem

import com.bkahlert.gametier.files.isWindowsReparsePoint

import io.kotest.matchers.shouldBe
import java.io.IOException
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal fun withTestDirectory(parent: Path? = null, action: (Path) -> Unit) {
    val root = if (parent == null) Files.createTempDirectory("gametier-test-")
        else Files.createTempDirectory(parent.toAbsolutePath(), "gametier-test-")
    try {
        action(root.toRealPath())
    } finally {
        deleteTestFixture(root)
    }
}

internal fun deleteTestFixture(root: Path) {
    if (Files.exists(root, NOFOLLOW_LINKS)) {
        Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes):
                java.nio.file.FileVisitResult {
                if (isWindowsReparsePoint(dir)) {
                    Files.delete(dir)
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE
                }
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes):
                java.nio.file.FileVisitResult {
                if (!attrs.isSymbolicLink && !isWindowsReparsePoint(file)) {
                    Files.getFileAttributeView(file, java.nio.file.attribute.DosFileAttributeView::class.java)
                        ?.setReadOnly(false)
                }
                Files.delete(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): java.nio.file.FileVisitResult {
                if (!isWindowsReparsePoint(file)) throw exc
                Files.delete(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): java.nio.file.FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }
}

internal fun withTestArchive(root: Path, name: String = "destination.zip", action: (Path) -> Unit) =
    FileSystems.newFileSystem(URI.create("jar:${root.resolve(name).toUri()}"), mapOf("create" to "true")).use {
        action(it.getPath("/"))
    }

internal fun createTestTree(path: Path): Path {
    Files.createDirectories(path.resolve("nested").resolve("empty"))
    Files.writeString(path.resolve("game.dat"), "game data")
    Files.writeString(path.resolve("nested").resolve("config.ini"), "settings")
    return path
}

internal fun assertTestTree(path: Path) {
    Files.readString(path.resolve("game.dat")) shouldBe "game data"
    Files.readString(path.resolve("nested").resolve("config.ini")) shouldBe "settings"
    Files.isDirectory(path.resolve("nested").resolve("empty")) shouldBe true
    Files.walk(path.toRealPath()).use { it.count() } shouldBe 5L
}

internal fun createTestJunction(link: Path, target: Path) {
    val process = ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), target.toString())
        .redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (process.waitFor() != 0) throw IOException("Could not create test junction: $output")
}
