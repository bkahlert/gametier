package com.bkahlert.gametier.ui

import com.bkahlert.gametier.files.FileMover
import com.bkahlert.gametier.game.DestinationInventory
import com.bkahlert.gametier.game.DestinationStatus
import com.bkahlert.gametier.game.IndexedPromotion
import com.bkahlert.gametier.game.PromotionIndex
import com.bkahlert.gametier.game.PromotionInventory
import com.bkahlert.gametier.game.TransferState
import com.bkahlert.gametier.testsupport.filesystem.createTestJunction
import com.bkahlert.gametier.testsupport.filesystem.requireSymlinkSupport
import com.bkahlert.gametier.testsupport.filesystem.withTestArchive
import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.DosFileAttributeView
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag

class FileSelectionPreparerTest {
    @Nested
    inner class Selection {
        @Test
        fun `on duplicate and descendant selections only the parent remains`() = withSelection { root, directory ->
            val folder = Files.createDirectory(root.resolve("game"))
            val child = Files.writeString(folder.resolve("part.dat"), "part")

            val result = prepare(listOf(child, folder, folder), directory)

            assertSoftly {
                result.items.map { it.original } shouldBe listOf(folder)
                result.items.single().status shouldBe ReviewStatus.READY
                result.items.single().destination shouldBe directory.resolve("game")
                result.adjustments shouldHaveSize 2
                result.problem shouldBe null
                Files.readString(child) shouldBe "part"
                Files.list(directory).use { it.count() } shouldBe 0L
            }
        }

        @Test
        fun `on normalized paths duplicate selection is reported`() = withSelection { root, directory ->
            val game = Files.writeString(root.resolve("game"), "data")

            val result = prepare(listOf(game, root.resolve("unused/../game")), directory)

            assertSoftly {
                result.items.map { it.original } shouldBe listOf(game)
                result.adjustments shouldHaveSize 1
            }
        }

        @Test
        fun `on parent aliases and case variants physical selections collapse`() = withSelection { root, directory ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val folder = Files.createDirectory(root.resolve("Original"))
            val game = Files.writeString(folder.resolve("Game.rom"), "data")
            val alias = root.resolve("alias")
            createTestJunction(alias, folder)

            val result = prepare(listOf(alias.resolve("game.ROM"), game), directory)

            assertSoftly {
                result.items.map { it.original } shouldBe listOf(game)
                result.items.single().destination shouldBe directory.resolve("Game.rom")
                result.adjustments shouldHaveSize 1
            }
        }

        @Test
        fun `on selecting a junction its target remains a separate ordinary entry`() = withSelection { root, directory ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val target = Files.createDirectory(root.resolve("data"))
            val link = root.resolve("link")
            createTestJunction(link, target)

            val result = prepare(listOf(link, target), directory)

            assertSoftly {
                result.items.map { it.original } shouldBe listOf(link, target)
                result.items.map { it.status } shouldBe listOf(ReviewStatus.BLOCKED, ReviewStatus.READY)
                result.items.first().message shouldContain "Unsupported"
                Files.isDirectory(target) shouldBe true
            }
        }

        @Test
        fun `on no destination the selection stays available for setup`() = withSelection { root, _ ->
            val game = Files.writeString(root.resolve("game"), "data")

            val result = prepare(listOf(game), null)

            assertSoftly {
                result.directory shouldBe null
                result.problem.orEmpty().shouldNotBeBlank()
                result.items.single().original shouldBe game
                result.items.single().destination shouldBe null
                result.items.single().status shouldBe ReviewStatus.BLOCKED
            }
        }

        @Test
        fun `on an empty selection no items are invented`() = withSelection { _, directory ->
            val result = prepare(emptyList(), directory)

            result.items shouldBe emptyList()
            result.adjustments shouldBe emptyList()
        }
    }

    @Nested
    inner class Sources {
        @Test
        fun `on missing originals each failure retains its path and other selections remain ready`() = withSelection { root, directory ->
            val missing = root.resolve("missing-parent/game")
            val game = Files.writeString(root.resolve("game"), "data")

            val result = prepare(listOf(missing, game), directory)

            assertSoftly {
                result.items.map { it.original } shouldBe listOf(missing, game)
                result.items.map { it.status } shouldBe listOf(ReviewStatus.BLOCKED, ReviewStatus.READY)
                result.items.first().message.shouldNotBeBlank()
                Files.exists(missing) shouldBe false
            }
        }

        @Test
        fun `on a filesystem root the source is blocked`() = withSelection { root, directory ->
            val result = prepare(listOf(root.root), directory)

            result.items.single().status shouldBe ReviewStatus.BLOCKED
            result.items.single().message shouldContain "root"
        }

        @Test
        fun `on unsupported nested entries source validation text is retained`() = withSelection { root, directory ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val game = Files.createDirectory(root.resolve("game"))
            val external = Files.createDirectory(root.resolve("external"))
            createTestJunction(game.resolve("nested"), external)
            val validation = shouldThrow<IOException> { FileMover.validateSource(game) }

            val result = prepare(listOf(game), directory)

            result.items.single().status shouldBe ReviewStatus.BLOCKED
            result.items.single().message shouldBe validation.message
            Files.list(external).use { it.count() } shouldBe 0L
        }

        @Test
        fun `on a nested destination even through an alias the source is blocked`() = withSelection { root, _ ->
            val game = Files.createDirectory(root.resolve("game"))
            val nested = Files.createDirectory(game.resolve("nested"))

            val result = prepare(listOf(game), nested)

            result.items.single().status shouldBe ReviewStatus.BLOCKED
            result.items.single().message shouldContain "inside"
            Files.list(nested).use { it.count() } shouldBe 0L
        }

        @Test
        fun `on a destination alias within the source the physical nesting is detected`() = withSelection { root, _ ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val game = Files.createDirectory(root.resolve("game"))
            val nested = Files.createDirectory(game.resolve("nested"))
            val alias = root.resolve("alias")
            createTestJunction(alias, nested)

            val result = prepare(listOf(game), alias)

            result.directory shouldBe nested
            result.items.single().status shouldBe ReviewStatus.BLOCKED
            result.items.single().message shouldContain "inside"
        }
    }

    @Nested
    inner class IndexedSelections {
        @Test
        fun `on indexed missing originals and promoted data both selections are skipped`() = withSelection { root, directory ->
            val original = root.resolve("missing")
            val data = Files.createDirectory(directory.resolve("game"))
            val child = Files.writeString(data.resolve("part"), "retained")
            PromotionIndex().write(directory, listOf(IndexedPromotion("game", original, TransferState.PROMOTED)))
            val before = Files.readString(directory.resolve(".gametier.json"))

            val result = prepare(listOf(original, child), directory)

            assertSoftly {
                result.items.map { it.original } shouldBe listOf(original, child)
                result.items.map { it.status } shouldBe listOf(ReviewStatus.SKIPPED, ReviewStatus.SKIPPED)
                result.items.forEach { it.message.shouldNotBeBlank() }
                Files.readString(directory.resolve(".gametier.json")) shouldBe before
                Files.readString(child) shouldBe "retained"
            }
        }

        @Test
        fun `on indexed originals in another destination the current addition skips them`() = withSelection { root, directory ->
            val other = Files.createDirectory(root.resolve("other"))
            val original = Files.writeString(root.resolve("game"), "retained")
            PromotionIndex().write(other, listOf(IndexedPromotion("game", original, TransferState.PROMOTING)))

            val result = prepare(listOf(original), directory, listOf(PromotionInventory.inspect(other)))

            result.items.single().status shouldBe ReviewStatus.SKIPPED
            result.items.single().message shouldContain "indexed"
            Files.readString(original) shouldBe "retained"
        }

        @Test
        fun `on a selected parent containing indexed data the addition is blocked`() = withSelection { root, directory ->
            val other = Files.createDirectory(root.resolve("other"))
            val data = Files.writeString(other.resolve("game"), "retained")
            PromotionIndex().write(other, listOf(IndexedPromotion("game", root.resolve("original"), TransferState.PROMOTED)))

            val result = prepare(listOf(other), directory, listOf(PromotionInventory.inspect(other)))

            result.items.single().status shouldBe ReviewStatus.BLOCKED
            result.items.single().message shouldContain "indexed"
            Files.readString(data) shouldBe "retained"
        }

        @Test
        fun `on absent indexed target data its name remains reserved`() = withSelection { root, directory ->
            val original = Files.writeString(root.resolve("game"), "retained")
            PromotionIndex().write(directory, listOf(IndexedPromotion("game", root.resolve("other-original"), TransferState.PROMOTING)))

            val result = prepare(listOf(original), directory)

            result.items.single().status shouldBe ReviewStatus.BLOCKED
            result.items.single().message shouldContain "indexed"
            Files.exists(directory.resolve("game")) shouldBe false
            Files.readString(original) shouldBe "retained"
        }

        @Test
        @Tag("symlink-success")
        fun `on a selected promotion link its original identity is preserved`() = withSelection { root, directory ->
            requireSymlinkSupport(root)
            val data = Files.createDirectory(directory.resolve("game"))
            val original = Files.createSymbolicLink(root.resolve("original"), data)
            PromotionIndex().write(directory, listOf(IndexedPromotion("game", original, TransferState.PROMOTED)))

            val result = prepare(listOf(original, data), directory)

            assertSoftly {
                result.items.map { it.original } shouldBe listOf(original, data)
                result.items.map { it.status } shouldBe listOf(ReviewStatus.SKIPPED, ReviewStatus.SKIPPED)
                result.adjustments shouldBe emptyList()
                Files.isSymbolicLink(original) shouldBe true
            }
        }

        @Test
        @Tag("symlink-success")
        fun `on an unindexed symbolic link source validation blocks the link`() = withSelection { root, directory ->
            requireSymlinkSupport(root)
            val data = Files.writeString(root.resolve("data"), "retained")
            val original = Files.createSymbolicLink(root.resolve("original"), data)

            val result = prepare(listOf(original), directory)

            result.items.single().original shouldBe original
            result.items.single().status shouldBe ReviewStatus.BLOCKED
            result.items.single().message shouldContain "Unsupported"
            Files.readString(data) shouldBe "retained"
        }
    }

    @Nested
    inner class Collisions {
        @Test
        fun `on occupied targets no file is overwritten`() = withSelection { root, directory ->
            val original = Files.writeString(root.resolve("game"), "source")
            val target = Files.writeString(directory.resolve("game"), "retained")

            val result = prepare(listOf(original), directory)

            result.items.single().status shouldBe ReviewStatus.BLOCKED
            result.items.single().message shouldContain "already exists"
            Files.readString(original) shouldBe "source"
            Files.readString(target) shouldBe "retained"
        }

        @Test
        fun `on dangling target junctions no overwrite is offered`() = withSelection { root, directory ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val original = Files.writeString(root.resolve("game"), "source")
            val target = Files.createDirectory(root.resolve("target"))
            createTestJunction(directory.resolve("game"), target)
            Files.delete(target)

            val result = prepare(listOf(original), directory)

            result.items.single().status shouldBe ReviewStatus.BLOCKED
            result.items.single().message shouldContain "already exists"
            Files.readString(original) shouldBe "source"
        }

        @Test
        fun `on same name selections every ambiguous item is blocked`() = withSelection { root, directory ->
            val first = Files.writeString(Files.createDirectory(root.resolve("first")).resolve("game"), "first")
            val second = Files.writeString(Files.createDirectory(root.resolve("second")).resolve("game"), "second")

            val result = prepare(listOf(first, second), directory)

            result.items.map { it.status } shouldBe listOf(ReviewStatus.BLOCKED, ReviewStatus.BLOCKED)
            result.items.forEach { it.message shouldContain "same destination" }
            Files.list(directory).use { it.count() } shouldBe 0L
        }

        @Test
        fun `on case variants destination name collisions follow native identity`() = withSelection { root, directory ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val first = Files.writeString(Files.createDirectory(root.resolve("first")).resolve("Game"), "first")
            val second = Files.writeString(Files.createDirectory(root.resolve("second")).resolve("game"), "second")

            val result = prepare(listOf(first, second), directory)

            result.items.map { it.status } shouldBe listOf(ReviewStatus.BLOCKED, ReviewStatus.BLOCKED)
        }

        @Test
        fun `on reserved originals or target paths pending work is not duplicated`() = withSelection { root, directory ->
            val first = Files.writeString(root.resolve("first"), "first")
            val second = Files.writeString(root.resolve("second"), "second")
            val third = Files.writeString(root.resolve("third"), "third")

            val result = prepare(listOf(first, second, third), directory, reserved = listOf(
                first to root.resolve("other/first"), root.resolve("unrelated") to directory.resolve("second"),
            ))

            result.items.map { it.status } shouldBe listOf(ReviewStatus.SKIPPED, ReviewStatus.BLOCKED, ReviewStatus.READY)
        }

        @Test
        fun `on a reserved destination alias the target remains occupied by queued work`() = withSelection { root, directory ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val original = Files.writeString(root.resolve("game"), "source")
            val alias = root.resolve("alias")
            createTestJunction(alias, directory)

            val result = prepare(listOf(original), directory, reserved = listOf(root.resolve("other") to alias.resolve("game")))

            result.items.single().status shouldBe ReviewStatus.BLOCKED
        }

        @Test
        fun `on selecting data inside a queued source the addition is skipped`() = withSelection { root, directory ->
            val parent = Files.createDirectory(root.resolve("parent"))
            val child = Files.writeString(parent.resolve("child"), "retained")

            val result = prepare(listOf(child), directory, reserved = listOf(parent to directory.resolve("parent")))

            result.items.single().status shouldBe ReviewStatus.SKIPPED
            result.items.single().message shouldContain "queued"
        }

        @Test
        fun `on a reserved parent selection queued descendants are blocked`() = withSelection { root, directory ->
            val parent = Files.createDirectory(root.resolve("parent"))
            val child = Files.writeString(parent.resolve("child"), "retained")

            val result = prepare(listOf(parent), directory, reserved = listOf(child to directory.resolve("child")))

            result.items.single().status shouldBe ReviewStatus.BLOCKED
            result.items.single().message shouldContain "queued"
        }

        @Test
        fun `on selecting reserved metadata names the fresh folder stays unregistered`() = withSelection { root, directory ->
            val original = Files.writeString(root.resolve(".gametier.json"), "source metadata")

            val result = prepare(listOf(original), directory)

            result.items.single().status shouldBe ReviewStatus.BLOCKED
            result.items.single().message shouldContain "index"
            Files.readString(original) shouldBe "source metadata"
            Files.exists(directory.resolve(".gametier.json")) shouldBe false
        }

        @Test
        fun `on source names that cannot be indexed review blocks before registration`() = withSelection { root, directory ->
            withTestArchive(root) { archive ->
                val original = Files.writeString(archive.resolve("game:stream"), "retained")

                val result = prepare(listOf(original), directory)

                result.items.single().status shouldBe ReviewStatus.BLOCKED
                result.items.single().message shouldContain "name"
                Files.readString(original) shouldBe "retained"
                Files.list(directory).use { it.count() } shouldBe 0L
            }
        }
    }

    @Nested
    inner class Destinations {
        @Test
        fun `on a fresh folder review does not create metadata or remember it`() = withSelection { root, directory ->
            val original = Files.writeString(root.resolve("game"), "source")
            val remembered = mutableSetOf<String>()

            val result = prepare(listOf(original), directory, remembered = remembered)

            assertSoftly {
                result.problem shouldBe null
                result.items.single().status shouldBe ReviewStatus.READY
                remembered shouldBe emptySet()
                Files.list(directory).use { it.count() } shouldBe 0L
                Files.readString(original) shouldBe "source"
            }
        }

        @Test
        fun `on a valid existing index review leaves its bytes untouched`() = withSelection { root, directory ->
            val original = Files.writeString(root.resolve("game"), "source")
            PromotionIndex().write(directory, emptyList())
            val before = Files.readAllBytes(directory.resolve(".gametier.json")).toList()

            val result = prepare(listOf(original), directory, remembered = setOf(directory.toString()))

            result.items.single().status shouldBe ReviewStatus.READY
            Files.readAllBytes(directory.resolve(".gametier.json")).toList() shouldBe before
        }

        @Test
        fun `on read-only metadata review blocks without changing the index`() = withSelection { root, directory ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val original = Files.writeString(root.resolve("game"), "source")
            PromotionIndex().write(directory, emptyList())
            val index = directory.resolve(".gametier.json")
            val before = Files.readAllBytes(index).toList()
            val attributes = Files.getFileAttributeView(index, DosFileAttributeView::class.java)
            attributes.setReadOnly(true)
            try {
                val result = prepare(listOf(original), directory, remembered = setOf(directory.toString()))

                assertSoftly {
                    result.items.single().status shouldBe ReviewStatus.BLOCKED
                    result.problem.orEmpty() shouldContain "metadata"
                    result.problem.orEmpty() shouldContain "writ"
                    Files.readAllBytes(index).toList() shouldBe before
                    attributes.readAttributes().isReadOnly shouldBe true
                    Files.readString(original) shouldBe "source"
                    Files.exists(directory.resolve("game")) shouldBe false
                }
            } finally {
                attributes.setReadOnly(false)
            }
        }

        @Test
        fun `on lost remembered metadata the folder is blocked without repair`() = withSelection { root, directory ->
            val original = Files.writeString(root.resolve("game"), "source")

            val result = prepare(listOf(original), directory, remembered = setOf(directory.toString()))

            result.items.single().status shouldBe ReviewStatus.BLOCKED
            result.problem.orEmpty() shouldContain "lost"
            Files.exists(directory.resolve(".gametier.json")) shouldBe false
        }

        @Test
        fun `on a remembered alias lost metadata still blocks first use`() = withSelection { root, directory ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val original = Files.writeString(root.resolve("game"), "source")
            val alias = root.resolve("alias")
            createTestJunction(alias, directory)

            val result = prepare(listOf(original), directory, remembered = setOf(alias.toString()))

            result.items.single().status shouldBe ReviewStatus.BLOCKED
            result.problem.orEmpty() shouldContain "lost"
            Files.list(directory).use { it.count() } shouldBe 0L
        }

        @Test
        fun `on invalid metadata the diagnostic and damaged index are retained`() = withSelection { root, directory ->
            val original = Files.writeString(root.resolve("game"), "source")
            val index = Files.writeString(directory.resolve(".gametier.json"), "damaged")

            val result = prepare(listOf(original), directory)

            result.items.single().status shouldBe ReviewStatus.BLOCKED
            result.problem.orEmpty() shouldContain "Cannot read promotion index"
            Files.readString(index) shouldBe "damaged"
        }

        @Test
        fun `on unavailable folders and non-directory destinations the source remains untouched`() = withSelection { root, _ ->
            val original = Files.writeString(root.resolve("game"), "source")
            val file = Files.writeString(root.resolve("file"), "retained")

            val unavailable = prepare(listOf(original), root.resolve("offline"))
            val nonDirectory = prepare(listOf(original), file)

            assertSoftly {
                unavailable.items.single().status shouldBe ReviewStatus.BLOCKED
                unavailable.problem.orEmpty().shouldNotBeBlank()
                nonDirectory.items.single().status shouldBe ReviewStatus.BLOCKED
                nonDirectory.problem.orEmpty().shouldNotBeBlank()
                Files.readString(original) shouldBe "source"
                Files.readString(file) shouldBe "retained"
                Files.exists(root.resolve("offline")) shouldBe false
            }
        }

        @Test
        fun `on relative or blank destinations settings validation is reused`() = withSelection { root, _ ->
            val original = Files.writeString(root.resolve("game"), "source")

            val relative = prepare(listOf(original), Path.of("relative"))
            val blank = prepare(listOf(original), Path.of(""))

            relative.items.single().status shouldBe ReviewStatus.BLOCKED
            relative.problem shouldBe "The default promotion folder must be an absolute path"
            blank.items.single().status shouldBe ReviewStatus.BLOCKED
            blank.problem shouldBe "The default promotion folder must not be blank"
        }

        @Test
        fun `on unwritable storage review blocks before metadata registration`() = withSelection { root, directory ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val original = Files.writeString(root.resolve("game"), "source")
            withDeniedWrites(directory) {
                val result = prepare(listOf(original), directory)

                result.items.single().status shouldBe ReviewStatus.BLOCKED
                result.problem.orEmpty() shouldContain "writ"
                Files.exists(directory.resolve(".gametier.json")) shouldBe false
                Files.readString(original) shouldBe "source"
            }
        }

        @Test
        fun `on stale selected destination inventory the current index is inspected`() = withSelection { root, directory ->
            val original = Files.writeString(root.resolve("game"), "source")
            val stale = DestinationInventory(directory, DestinationStatus.AVAILABLE)
            Files.writeString(directory.resolve(".gametier.json"), "damaged")

            val result = prepare(listOf(original), directory, listOf(stale))

            result.items.single().status shouldBe ReviewStatus.BLOCKED
            result.problem.orEmpty() shouldContain "Cannot read promotion index"
        }
    }
}

private fun withSelection(action: (Path, Path) -> Unit) = withTestDirectory { root ->
    action(root, Files.createDirectory(root.resolve("destination")))
}

private fun prepare(
    paths: List<Path>,
    directory: Path?,
    inventory: List<DestinationInventory> = emptyList(),
    remembered: Set<String> = emptySet(),
    reserved: List<Pair<Path, Path>> = emptyList(),
): SelectionReview = runBlocking { FileSelectionPreparer().prepare(paths, directory, inventory, remembered, reserved) }

private fun withDeniedWrites(directory: Path, action: () -> Unit) {
    val view = Files.getFileAttributeView(directory, AclFileAttributeView::class.java)
    val original = view.acl
    val denied = AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(Files.getOwner(directory))
        .setPermissions(AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA).build()
    try {
        view.acl = listOf(denied) + original
        assumeTrue(!Files.isWritable(directory), "Temporary directory write denial unavailable")
        action()
    } finally {
        view.acl = original
    }
}
