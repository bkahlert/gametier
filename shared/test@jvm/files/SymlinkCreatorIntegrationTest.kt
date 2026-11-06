package com.bkahlert.gametier.files

import com.bkahlert.gametier.testsupport.filesystem.assertTestTree
import com.bkahlert.gametier.testsupport.filesystem.createTestTree
import com.bkahlert.gametier.testsupport.filesystem.requireSymlinkSupport
import com.bkahlert.gametier.testsupport.filesystem.symlinkCreationFailure
import com.bkahlert.gametier.testsupport.filesystem.testVolumeCases
import com.bkahlert.gametier.testsupport.filesystem.testVolumePairCases

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory

class SymlinkCreatorIntegrationTest {
    @Nested
    inner class AcrossVolumes {
        @TestFactory
        @Tag("symlink-success")
        fun `on linking files target contents are accessible`() = testVolumePairCases { linkRoot, targetRoot ->
            requireSymlinkSupport(linkRoot)
            val target = Files.writeString(targetRoot.resolve("game.dat"), "game data")
            val link = linkRoot.resolve("game.dat")

            val result = SymlinkCreator.create(link, target)

            assertSoftly {
                result shouldBe Unit
                Files.isSymbolicLink(link) shouldBe true
                Files.readSymbolicLink(link) shouldBe target.toRealPath()
                Files.readString(link) shouldBe "game data"
                Files.readString(target) shouldBe "game data"
            }
        }

        @TestFactory
        @Tag("symlink-success")
        fun `on linking directories nested entries are accessible`() = testVolumePairCases { linkRoot, targetRoot ->
            requireSymlinkSupport(linkRoot)
            val target = createTestTree(targetRoot.resolve("game"))
            val link = linkRoot.resolve("game")

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

        @TestFactory
        fun `on occupied locations existing entries and targets are retained`() = testVolumePairCases { linkRoot, targetRoot ->
            val target = createTestTree(targetRoot.resolve("game"))
            val directory = createTestTree(linkRoot.resolve("game"))
            val file = Files.writeString(linkRoot.resolve("game.dat"), "existing")
            listOf(directory, file).forEach { link ->
                val result = shouldThrow<IOException> { SymlinkCreator.create(link, target) }

                assertSoftly {
                    assertLinkFailure(result, link, target, "validation")
                    assertTestTree(directory)
                    Files.readString(file) shouldBe "existing"
                    assertTestTree(target)
                }
            }
        }

        @TestFactory
        fun `on unavailable creation no fallback or target changes occur`() = testVolumePairCases { linkRoot, targetRoot ->
            val failure = symlinkCreationFailure(linkRoot)
            assumeTrue(failure is IOException, "Native symlink creation is available")
            val target = createTestTree(targetRoot.resolve("game"))
            val link = linkRoot.resolve("game")

            val result = shouldThrow<IOException> { SymlinkCreator.create(link, target) }

            assertSoftly {
                assertLinkFailure(result, link, target, "creation")
                result.cause!!.javaClass shouldBe failure!!.javaClass
                Files.exists(link, NOFOLLOW_LINKS) shouldBe false
                assertTestTree(target)
            }
        }
    }

    @Nested
    @Tag("symlink-success")
    inner class WithinVolumes {
        @TestFactory
        fun `on each volume files and directories are accessible through their links`() = testVolumeCases { root ->
            requireSymlinkSupport(root)
            val target = createTestTree(root.resolve("target"))
            val directoryLink = root.resolve("game")
            val fileTarget = target.resolve("game.dat")
            val fileLink = root.resolve("game.dat")

            val directoryResult = SymlinkCreator.create(directoryLink, target)
            val fileResult = SymlinkCreator.create(fileLink, fileTarget)

            assertSoftly {
                directoryResult shouldBe Unit
                fileResult shouldBe Unit
                Files.isSymbolicLink(directoryLink) shouldBe true
                Files.readSymbolicLink(directoryLink) shouldBe target.toRealPath()
                Files.isSymbolicLink(fileLink) shouldBe true
                Files.readSymbolicLink(fileLink) shouldBe fileTarget.toRealPath()
                Files.readString(directoryLink.resolve("nested/config.ini")) shouldBe "settings"
                Files.readString(fileLink) shouldBe "game data"
                assertTestTree(target)
            }
        }
    }
}
