package com.bkahlert.gametier.game

import com.bkahlert.gametier.files.SymlinkCreator
import com.bkahlert.gametier.testsupport.filesystem.assertTestTree
import com.bkahlert.gametier.testsupport.filesystem.createTestTree
import com.bkahlert.gametier.testsupport.filesystem.requireSymlinkSupport
import com.bkahlert.gametier.testsupport.filesystem.testVolumeCases
import com.bkahlert.gametier.testsupport.filesystem.testVolumePairCases

import com.sun.nio.file.ExtendedOpenOption
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory

class DemoterIntegrationTest {
    @Nested
    @Tag("symlink-success")
    inner class Demotion {
        @TestFactory
        fun `on files across volumes the original entry replaces its link`() = testVolumePairCases { from, to ->
            assertDemotion(from, to, false)
        }

        @TestFactory
        fun `on trees across volumes the original entry replaces its link`() = testVolumePairCases { from, to ->
            assertDemotion(from, to, true)
        }

        @TestFactory
        fun `on each volume files and trees can be demoted`() = testVolumeCases { root ->
            assertDemotion(root, root, false)
            assertDemotion(root, root, true)
        }

        @TestFactory
        fun `on partial copy failure both trees survive without a replacement link`() = testVolumePairCases { from, to ->
            requireSymlinkSupport(from)
            val target = createTestTree(to.resolve("target"))
            val original = from.resolve("game")
            SymlinkCreator.create(original, target)
            FileChannel.open(target.resolve("game.dat"), READ, ExtendedOpenOption.NOSHARE_READ).use {
                val result = shouldThrow<IOException> { Demoter.demote(original) }

                assertTransferFailure(result, original, target, "move")
                Files.isSymbolicLink(original) shouldBe false
                Files.isDirectory(original) shouldBe true
            }
            assertTestTree(target)
        }

        @TestFactory
        fun `on cleanup failure the restored file and target both survive`() = testVolumePairCases { from, to ->
            requireSymlinkSupport(from)
            val target = Files.writeString(to.resolve("target.rom"), "game data")
            val original = from.resolve("game.rom")
            SymlinkCreator.create(original, target)
            FileChannel.open(target, READ, ExtendedOpenOption.NOSHARE_DELETE).use {
                val result = shouldThrow<IOException> { Demoter.demote(original) }

                assertTransferFailure(result, original, target, "move")
                Files.isSymbolicLink(original) shouldBe false
                Files.readString(original) shouldBe "game data"
                Files.readString(target) shouldBe "game data"
            }
        }
    }
}

private fun assertDemotion(from: Path, to: Path, directory: Boolean) {
    requireSymlinkSupport(from)
    val name = if (directory) "game" else "game.rom"
    val original = from.resolve(name)
    if (directory) createTestTree(original) else Files.writeString(original, "game data")
    val target = to.resolve("target-$name")
    Promoter.promote(original, target)

    val result = Demoter.demote(original)

    assertSoftly {
        result shouldBe Unit
        Files.isSymbolicLink(original) shouldBe false
        Files.exists(target, NOFOLLOW_LINKS) shouldBe false
        if (directory) assertTestTree(original) else Files.readString(original) shouldBe "game data"
    }
}
