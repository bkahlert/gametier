package com.bkahlert.gametier

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag

class SymlinkCreatorTest {
    @Nested
    @Tag("symlink-success")
    inner class Creation {
        @Test
        fun `on linking a file its contents are accessible through an absolute symlink`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val target = Files.writeString(root.resolve("target"), "game data")
            val link = root.resolve("link")

            val result = SymlinkCreator.create(link, target)

            result shouldBe Unit
            Files.isSymbolicLink(link) shouldBe true
            Files.readSymbolicLink(link) shouldBe target.toRealPath()
            Files.readString(link) shouldBe "game data"
        }

        @Test
        fun `on linking a directory nested entries remain accessible`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val target = createTestTree(root.resolve("target"))
            val link = root.resolve("link")

            val result = SymlinkCreator.create(link, target)

            assertSoftly {
                result shouldBe Unit
                Files.isSymbolicLink(link) shouldBe true
                Files.readSymbolicLink(link) shouldBe target.toRealPath()
                Files.readString(link.resolve("game.dat")) shouldBe "game data"
                Files.readString(link.resolve("nested/config.ini")) shouldBe "settings"
                Files.isDirectory(link.resolve("nested/empty")) shouldBe true
                assertTestTree(target)
            }
        }

        @Test
        fun `on relative inputs normalized absolute targets are stored`() = withTestDirectory(Path.of("")) { root ->
            requireSymlinkSupport(root)
            val target = Files.writeString(root.resolve("target"), "game data")
            val workingDirectory = Path.of("").toAbsolutePath()
            val link = workingDirectory.relativize(root.resolve("unused/../link"))
            val relativeTarget = workingDirectory.relativize(root.resolve("unused/../target"))

            val result = SymlinkCreator.create(link, relativeTarget)

            assertSoftly {
                result shouldBe Unit
                Files.readSymbolicLink(root.resolve("link")) shouldBe target.toRealPath()
                Files.readString(root.resolve("link")) shouldBe "game data"
            }
        }

        @Test
        fun `on parent aliases canonical paths are used`() = withTestDirectory { root ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            requireSymlinkSupport(root)
            val directory = Files.createDirectory(root.resolve("directory"))
            val target = Files.writeString(directory.resolve("target"), "game data")
            val alias = root.resolve("alias")
            createTestJunction(alias, directory)

            val result = SymlinkCreator.create(alias.resolve("link"), alias.resolve("target"))

            result shouldBe Unit
            Files.readSymbolicLink(directory.resolve("link")) shouldBe target.toRealPath()
            Files.readString(alias.resolve("link")) shouldBe "game data"
        }

        @Test
        fun `on links inside the target directory contents are not rejected`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val target = Files.createDirectory(root.resolve("target"))
            Files.createSymbolicLink(target.resolve("dangling"), root.resolve("missing"))
            val link = root.resolve("link")

            val result = SymlinkCreator.create(link, target)

            result shouldBe Unit
            Files.isSymbolicLink(link) shouldBe true
            Files.isSymbolicLink(link.resolve("dangling")) shouldBe true
        }
    }

    @Nested
    inner class Validation {
        @Test
        fun `on a missing target no link is created`() = withTestDirectory { root ->
            val target = root.resolve("missing")
            val link = root.resolve("link")

            val result = shouldThrow<IOException> { SymlinkCreator.create(link, target) }

            assertLinkFailure(result, link, target, "validation")
            Files.exists(link, NOFOLLOW_LINKS) shouldBe false
        }

        @Test
        fun `on missing parents no directories are created`() = withTestDirectory { root ->
            val file = Files.writeString(root.resolve("target"), "game data")
            listOf(root.resolve("missing/link") to file, root.resolve("link") to root.resolve("missing/target"))
                .forEach { (link, target) ->
                    val result = shouldThrow<IOException> { SymlinkCreator.create(link, target) }

                    assertLinkFailure(result, link, target, "validation")
                    Files.exists(root.resolve("missing")) shouldBe false
                    Files.exists(link, NOFOLLOW_LINKS) shouldBe false
                    Files.readString(file) shouldBe "game data"
                }
        }

        @Test
        fun `on occupied file and directory locations existing entries are retained`() = withTestDirectory { root ->
            val target = Files.writeString(root.resolve("target"), "game data")
            val file = Files.writeString(root.resolve("file"), "existing")
            val directory = createTestTree(root.resolve("directory"))
            listOf(file, directory).forEach { link ->
                val result = shouldThrow<IOException> { SymlinkCreator.create(link, target) }

                assertLinkFailure(result, link, target, "validation")
                Files.readString(file) shouldBe "existing"
                assertTestTree(directory)
                Files.readString(target) shouldBe "game data"
            }
        }

        @Test
        @Tag("symlink-success")
        fun `on an existing symlink including a dangling link no overwrite occurs`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val target = Files.writeString(root.resolve("target"), "game data")
            listOf(target, root.resolve("missing")).forEachIndexed { index, existingTarget ->
                val link = Files.createSymbolicLink(root.resolve("link-$index"), existingTarget)

                val result = shouldThrow<IOException> { SymlinkCreator.create(link, target) }

                assertLinkFailure(result, link, target, "validation")
                Files.readSymbolicLink(link) shouldBe existingTarget
                Files.readString(target) shouldBe "game data"
            }
        }

        @Test
        fun `on a dangling junction collision it is retained`() = withTestDirectory { root ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val target = Files.writeString(root.resolve("target"), "game data")
            val missing = Files.createDirectory(root.resolve("missing"))
            val link = root.resolve("link")
            createTestJunction(link, missing)
            Files.delete(missing)

            val result = shouldThrow<IOException> { SymlinkCreator.create(link, target) }

            assertLinkFailure(result, link, target, "validation")
            isWindowsReparsePoint(link) shouldBe true
            Files.readString(target) shouldBe "game data"
        }

        @Test
        @Tag("symlink-success")
        fun `on a symlink target no chain is created`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val file = Files.writeString(root.resolve("file"), "game data")
            val target = Files.createSymbolicLink(root.resolve("target"), file)
            val link = root.resolve("link")

            val result = shouldThrow<IOException> { SymlinkCreator.create(link, target) }

            assertLinkFailure(result, link, target, "validation")
            Files.exists(link, NOFOLLOW_LINKS) shouldBe false
            Files.readString(file) shouldBe "game data"
        }

        @Test
        fun `on a junction target no chain is created`() = withTestDirectory { root ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val directory = createTestTree(root.resolve("directory"))
            val target = root.resolve("target")
            createTestJunction(target, directory)
            val link = root.resolve("link")

            val result = shouldThrow<IOException> { SymlinkCreator.create(link, target) }

            assertLinkFailure(result, link, target, "validation")
            Files.exists(link, NOFOLLOW_LINKS) shouldBe false
            assertTestTree(directory)
        }

        @Test
        fun `on a link inside its target recursive traversal is prevented`() = withTestDirectory { root ->
            val target = createTestTree(root.resolve("target"))
            val link = target.resolve("nested/link")

            val result = shouldThrow<IOException> { SymlinkCreator.create(link, target) }

            assertLinkFailure(result, link, target, "validation")
            Files.exists(link, NOFOLLOW_LINKS) shouldBe false
            assertTestTree(target)
        }

        @Test
        fun `on an aliased parent inside the target recursive traversal is prevented`() = withTestDirectory { root ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val target = createTestTree(root.resolve("target"))
            val alias = root.resolve("alias")
            createTestJunction(alias, target.resolve("nested"))
            val link = alias.resolve("link")

            val result = shouldThrow<IOException> { SymlinkCreator.create(link, target) }

            assertLinkFailure(result, link, target, "validation")
            Files.exists(link, NOFOLLOW_LINKS) shouldBe false
            assertTestTree(target)
        }

        @Test
        fun `on a short-name target alias recursive traversal is prevented`() = withTestDirectory { root ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val target = createTestTree(root.resolve("Long Target Directory"))
            val process = ProcessBuilder("cmd.exe", "/d", "/c", "for %I in (\"$target\") do @echo %~sI")
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { output }
            val alias = Path.of(output.trim())
            assumeTrue(alias.fileName.toString() != target.fileName.toString(), "Windows short names unavailable")
            val link = target.resolve("nested/link")

            val result = shouldThrow<IOException> { SymlinkCreator.create(link, alias) }

            assertLinkFailure(result, link, alias, "validation")
            Files.exists(link, NOFOLLOW_LINKS) shouldBe false
            assertTestTree(target)
        }

        @Test
        fun `on filesystem roots no mutation occurs`() = withTestDirectory { root ->
            val file = Files.writeString(root.resolve("target"), "game data")
            listOf(root.resolve("link") to root.root, root.root to file).forEach { (link, target) ->
                val result = shouldThrow<IOException> { SymlinkCreator.create(link, target) }

                assertLinkFailure(result, link, target, "validation")
                Files.readString(file) shouldBe "game data"
            }
            Files.exists(root.resolve("link"), NOFOLLOW_LINKS) shouldBe false
        }
    }

    @Nested
    inner class Failures {
        @Test
        fun `on unavailable creation the native cause is retained without fallback`() = withTestDirectory { root ->
            val failure = symlinkCreationFailure(root)
            assumeTrue(failure is IOException, "Native symlink creation is available")
            val target = createTestTree(root.resolve("target"))
            val link = root.resolve("link")

            val result = shouldThrow<IOException> { SymlinkCreator.create(link, target) }

            assertLinkFailure(result, link, target, "creation")
            result.cause.shouldNotBeNull().javaClass shouldBe failure!!.javaClass
            Files.exists(link, NOFOLLOW_LINKS) shouldBe false
            assertTestTree(target)
        }

        @Test
        fun `on an unsupported filesystem creation reports its cause`() = withTestDirectory { root ->
            withTestArchive(root) { archive ->
                val target = Files.writeString(archive.resolve("target"), "game data")
                val link = archive.resolve("link")

                val result = shouldThrow<IOException> { SymlinkCreator.create(link, target) }

                assertLinkFailure(result, link, target, "creation")
                (result.cause is UnsupportedOperationException) shouldBe true
                Files.exists(link, NOFOLLOW_LINKS) shouldBe false
                Files.readString(target) shouldBe "game data"
            }
        }

        @Test
        fun `on incompatible filesystem providers failure is contextual`() = withTestDirectory { root ->
            withTestArchive(root) { archive ->
                val target = Files.writeString(archive.resolve("target"), "game data")
                val link = root.resolve("link")

                val result = shouldThrow<IOException> { SymlinkCreator.create(link, target) }

                assertLinkFailure(result, link, target, "validation")
                Files.exists(link, NOFOLLOW_LINKS) shouldBe false
                Files.readString(target) shouldBe "game data"
            }
        }
    }
}

internal fun assertLinkFailure(error: IOException, link: Path, target: Path, phase: String) = assertSoftly {
    error.message.orEmpty() shouldContain phase
    error.message.orEmpty() shouldContain link.toString()
    error.message.orEmpty() shouldContain target.toString()
    error.cause.shouldNotBeNull()
}
