package com.bkahlert.gametier.game

import com.bkahlert.gametier.testsupport.filesystem.createTestJunction
import com.bkahlert.gametier.testsupport.filesystem.requireSymlinkSupport
import com.bkahlert.gametier.testsupport.filesystem.withTestArchive
import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag

class PromotionEligibilityTest {
    @Nested
    inner class BackupDirectories {
        @Test
        fun `on backup storage and its descendants promotion is blocked without metadata`() = withTestDirectory { root ->
            val storage = Files.createDirectory(root.resolve(".gametier"))
            val folder = Files.createDirectories(storage.resolve("backups/game"))
            val file = Files.writeString(folder.resolve("data.rom"), "retained")

            val results = listOf(storage, folder, file).map { PromotionEligibility.check(it) }

            assertSoftly {
                results.forEach { it.orEmpty() shouldContain "backup storage" }
                Files.readString(file) shouldBe "retained"
            }
        }

        @Test
        fun `on a differently cased backup directory its descendants remain blocked`() = withTestDirectory { root ->
            val folder = Files.createDirectory(root.resolve(".GAMETIER"))
            val file = Files.writeString(folder.resolve("game"), "retained")

            val result = PromotionEligibility.check(file)

            result.orEmpty() shouldContain "backup storage"
        }

        @Test
        fun `on lexical and canonical backup ancestors aliases cannot bypass exclusion`() = withTestDirectory { root ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val storage = Files.createDirectory(root.resolve(".gametier"))
            val ordinary = Files.createDirectory(root.resolve("ordinary"))
            Files.writeString(storage.resolve("backup"), "snapshot")
            Files.writeString(ordinary.resolve("game"), "game")
            val outsideAlias = root.resolve("alias")
            val insideAlias = storage.resolve("alias")
            createTestJunction(outsideAlias, storage)
            createTestJunction(insideAlias, ordinary)

            val physical = PromotionEligibility.check(outsideAlias.resolve("backup"))
            val lexical = PromotionEligibility.check(insideAlias.resolve("game"))

            assertSoftly {
                physical.orEmpty() shouldContain "backup storage"
                lexical.orEmpty() shouldContain "backup storage"
            }
        }

        @Test
        fun `on similar directory names and a regular file named gametier promotion remains eligible`() = withTestDirectory { root ->
            val folder = Files.createDirectory(root.resolve(".gametier-other"))
            val file = Files.writeString(root.resolve(".gametier"), "game")
            val child = Files.writeString(folder.resolve("game"), "game")

            val results = listOf(file, child).map { PromotionEligibility.check(it) }

            results shouldBe listOf(null, null)
        }
    }

    @Nested
    inner class ManagedDirectories {
        @Test
        fun `on a selected managed directory any marker contents block it`() = withTestDirectory { root ->
            val marker = root.resolve(".gametier.json")
            listOf("", "malformed", "{}").forEach { contents ->
                Files.writeString(marker, contents)

                val result = PromotionEligibility.check(root)

                result.orEmpty() shouldContain marker.toString()
                Files.readString(marker) shouldBe contents
            }
        }

        @Test
        fun `on a marker directory its presence still blocks descendants`() = withTestDirectory { root ->
            val marker = Files.createDirectory(root.resolve(".gametier.json"))
            val game = Files.writeString(root.resolve("game"), "retained")

            val result = PromotionEligibility.check(game)

            result.orEmpty() shouldContain marker.toString()
            Files.isDirectory(marker) shouldBe true
            Files.readString(game) shouldBe "retained"
        }

        @Test
        fun `on a marker only inside a descendant eligibility does not scan it`() = withTestDirectory { root ->
            val nested = Files.createDirectory(root.resolve("nested"))
            Files.writeString(nested.resolve(".gametier.json"), "malformed")

            val result = PromotionEligibility.check(root)

            result shouldBe null
        }

        @Test
        @Tag("symlink-success")
        fun `on a dangling marker link its presence blocks the source`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val marker = Files.createSymbolicLink(root.resolve(".gametier.json"), root.resolve("missing"))
            val game = Files.writeString(root.resolve("game"), "retained")

            val result = PromotionEligibility.check(game)

            result.orEmpty() shouldContain marker.toString()
            Files.isSymbolicLink(marker) shouldBe true
        }
    }

    @Nested
    inner class Sources {
        @Test
        fun `on ordinary files and directories eligibility leaves their contents untouched`() = withTestDirectory { root ->
            val file = Files.writeString(root.resolve("game"), "retained")
            val directory = Files.createDirectory(root.resolve("folder"))

            val fileResult = PromotionEligibility.check(file)
            val directoryResult = PromotionEligibility.check(directory)

            assertSoftly {
                fileResult shouldBe null
                directoryResult shouldBe null
                Files.readString(file) shouldBe "retained"
                Files.list(root).use { it.count() } shouldBe 2L
                Files.list(directory).use { it.count() } shouldBe 0L
            }
        }

        @Test
        fun `on a filesystem root eligibility blocks it`() = withTestDirectory { root ->
            val result = PromotionEligibility.check(root.root)

            result.orEmpty() shouldContain "root"
        }

        @Test
        fun `on missing sources or missing ancestors eligibility blocks without creating them`() = withTestDirectory { root ->
            listOf(root.resolve("missing"), root.resolve("missing-parent/game")).forEach { path ->
                val result = PromotionEligibility.check(path)

                result.orEmpty() shouldContain path.toString()
                Files.exists(path) shouldBe false
            }
            Files.list(root).use { it.count() } shouldBe 0L
        }

        @Test
        fun `on a junction source eligibility blocks its target from becoming the selection`() = withTestDirectory { root ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val target = Files.createDirectory(root.resolve("target"))
            val link = root.resolve("link")
            createTestJunction(link, target)

            val result = PromotionEligibility.check(link)

            result.orEmpty() shouldContain "Unsupported"
            Files.list(target).use { it.count() } shouldBe 0L
        }

        @Test
        @Tag("symlink-success")
        fun `on a symbolic link source eligibility blocks the link`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            val target = Files.writeString(root.resolve("target"), "retained")
            val link = Files.createSymbolicLink(root.resolve("link"), target)

            val result = PromotionEligibility.check(link)

            result.orEmpty() shouldContain "Unsupported"
            Files.readString(target) shouldBe "retained"
        }
    }

    @Nested
    inner class InspectionFailures {
        @Test
        fun `on an unreadable ancestor eligibility blocks the source`() = withTestDirectory { root ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val ancestor = Files.createDirectory(root.resolve("ancestor"))
            val game = Files.writeString(Files.createDirectory(ancestor.resolve("parent")).resolve("game"), "retained")
            withDeniedMetadata(ancestor) {
                Files.isReadable(ancestor) shouldBe false

                val result = PromotionEligibility.check(game)

                result.orEmpty() shouldContain "Cannot inspect"
                result.orEmpty() shouldContain ancestor.toString()
            }
            Files.readString(game) shouldBe "retained"
        }

        @Test
        fun `on an unreadable marker its presence still blocks the source`() = withTestDirectory { root ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val marker = Files.writeString(root.resolve(".gametier.json"), "retained")
            val game = Files.writeString(root.resolve("game"), "game data")
            withDeniedMetadata(marker) {
                Files.isReadable(marker) shouldBe false

                val result = PromotionEligibility.check(game)

                result.orEmpty() shouldContain "index exists"
                result.orEmpty() shouldContain marker.toString()
            }
            Files.readString(marker) shouldBe "retained"
            Files.readString(game) shouldBe "game data"
        }

        @Test
        fun `on a closed source filesystem eligibility returns a contextual failure`() = withTestDirectory { root ->
            lateinit var game: Path
            withTestArchive(root) { archive -> game = Files.writeString(archive.resolve("game"), "retained") }

            val result = PromotionEligibility.check(game)

            result.orEmpty() shouldContain "Cannot inspect"
            result.orEmpty() shouldContain "game"
        }
    }
}

private fun withDeniedMetadata(path: Path, action: () -> Unit) {
    val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
    val original = view.acl
    val denied = AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(Files.getOwner(path))
        .setPermissions(AclEntryPermission.READ_ATTRIBUTES, AclEntryPermission.READ_DATA).build()
    try {
        view.acl = listOf(denied) + original
        action()
    } finally {
        view.acl = original
    }
}
