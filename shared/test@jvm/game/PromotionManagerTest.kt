package com.bkahlert.gametier.game

import com.bkahlert.gametier.settings.DataStoreAppSettings
import com.bkahlert.gametier.settings.MemoryPreferencesStore
import com.bkahlert.gametier.testsupport.filesystem.requireSymlinkSupport
import com.bkahlert.gametier.testsupport.filesystem.createTestJunction
import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag

class PromotionManagerTest {
    @Nested
    inner class Registration {
        @Test
        fun `on default parent aliases inventory reports each physical directory once`() = withTestDirectory { root ->
            runBlocking {
                val settings = DataStoreAppSettings(MemoryPreferencesStore())
                val folder = Files.createDirectory(root.resolve("games"))
                val alias = root.resolve("alias")
                createTestJunction(alias, folder)
                val manager = PromotionManager(settings)
                manager.registerDirectory(folder)
                settings.setDefaultPromotionFolder(alias.toString())

                val result = manager.inventory()

                result.map { it.directory } shouldBe listOf(folder)
            }
        }

        @Test
        fun `on selecting a fresh default it can be initialized and previous used destinations remain visible`() = withTestDirectory { root ->
            runBlocking {
                val settings = DataStoreAppSettings(MemoryPreferencesStore())
                val first = Files.createDirectory(root.resolve("first"))
                val second = Files.createDirectory(root.resolve("second"))
                val manager = PromotionManager(settings)
                settings.setDefaultPromotionFolder(first.toString())
                val selected = manager.inventory()

                val registered = manager.registerDirectory(first)
                settings.setDefaultPromotionFolder(second.toString())
                val result = manager.inventory()

                assertSoftly {
                    selected.single().directory shouldBe first
                    selected.single().status shouldBe DestinationStatus.MISSING_INDEX
                    registered shouldBe Unit
                    result.associate { it.directory to it.status } shouldBe mapOf(
                        first to DestinationStatus.AVAILABLE, second to DestinationStatus.MISSING_INDEX,
                    )
                }
            }
        }

        @Test
        fun `on first use existing content stays unrecognized and the directory is remembered`() = withTestDirectory { root ->
            runBlocking {
                val settings = DataStoreAppSettings(MemoryPreferencesStore())
                val manager = PromotionManager(settings)
                val extra = Files.writeString(root.resolve("extra"), "unrelated")

                val registered = manager.registerDirectory(root)
                val result = manager.inventory()

                assertSoftly {
                    registered shouldBe Unit
                    settings.promotionFolders.first() shouldBe setOf(root.toString())
                    result.single().status shouldBe DestinationStatus.AVAILABLE
                    result.single().unrecognized shouldBe listOf(extra)
                    Files.isHidden(root.resolve(".gametier.json")) shouldBe true
                    Files.readString(extra) shouldBe "unrelated"
                }
            }
        }

        @Test
        fun `on lost or damaged remembered metadata registration refuses to reset it`() = withTestDirectory { root ->
            runBlocking {
                val settings = DataStoreAppSettings(MemoryPreferencesStore())
                val manager = PromotionManager(settings)
                manager.registerDirectory(root)
                val index = root.resolve(".gametier.json")
                Files.delete(index)

                val missing = shouldThrow<IOException> { manager.registerDirectory(root) }
                Files.writeString(index, "damaged")
                val damaged = shouldThrow<IOException> { manager.registerDirectory(root) }

                assertSoftly {
                    missing.message.isNullOrBlank() shouldBe false
                    damaged.message.isNullOrBlank() shouldBe false
                    Files.readString(index) shouldBe "damaged"
                }
            }
        }

        @Test
        fun `on unavailable remembered directories inventory retains their identity`() = withTestDirectory { root ->
            runBlocking {
                val settings = DataStoreAppSettings(MemoryPreferencesStore())
                val folder = root.resolve("offline")
                settings.rememberPromotionFolder(folder.toString())

                val result = PromotionManager(settings).inventory()

                assertSoftly {
                    result.single().directory shouldBe folder
                    result.single().status shouldBe DestinationStatus.UNAVAILABLE
                    settings.promotionFolders.first() shouldBe setOf(folder.toString())
                }
            }
        }

        @Test
        fun `on concurrent registrations through different managers all directories survive`() = withTestDirectory { root ->
            runBlocking {
                val settings = DataStoreAppSettings(MemoryPreferencesStore())
                val folders = List(4) { Files.createDirectory(root.resolve("games-$it")) }

                val registered = coroutineScope {
                    folders.map { folder -> async(Dispatchers.Default) { PromotionManager(settings).registerDirectory(folder) } }.awaitAll()
                }
                val result = PromotionManager(settings).inventory()

                assertSoftly {
                    registered shouldBe List(4) { Unit }
                    result.map { it.directory }.toSet() shouldBe folders.toSet()
                    result.map { it.status } shouldBe List(4) { DestinationStatus.AVAILABLE }
                }
            }
        }
    }

    @Nested
    inner class Failures {
        @Test
        fun `on discovering an existing index during demotion interrupted entries become visible`() = withTestDirectory { root ->
            runBlocking {
                val settings = DataStoreAppSettings(MemoryPreferencesStore())
                PromotionIndex().write(root, listOf(IndexedPromotion("game", root.resolve("origin"), TransferState.DEMOTING)))
                val manager = PromotionManager(settings)

                val error = shouldThrow<IOException> { manager.demote(root, "game") }
                val result = manager.inventory()

                assertSoftly {
                    error.message.isNullOrBlank() shouldBe false
                    result.single().directory shouldBe root
                    result.single().promotions.single().condition shouldBe PromotionCondition.NEEDS_ATTENTION
                    settings.promotionFolders.first() shouldBe setOf(root.toString())
                }
            }
        }

        @Test
        fun `on settings write failure existing metadata and game data remain untouched`() = withTestDirectory { root ->
            runBlocking {
                val original = Files.writeString(root.resolve("origin"), "retained")
                val records = listOf(IndexedPromotion("game", original, TransferState.PROMOTED))
                PromotionIndex().write(root, records)
                val store = MemoryPreferencesStore().apply { writeFailure = IOException("settings unavailable") }
                val settings = DataStoreAppSettings(store)

                val error = shouldThrow<IOException> { PromotionManager(settings).demote(root, "game") }
                val result = PromotionIndex().read(root)

                assertSoftly {
                    error.message shouldBe "settings unavailable"
                    result shouldBe records
                    settings.promotionFolders.first() shouldBe emptySet()
                    Files.readString(original) shouldBe "retained"
                }
            }
        }

        @Test
        fun `on demoting without metadata no index or remembered directory is created`() = withTestDirectory { root ->
            runBlocking {
                val settings = DataStoreAppSettings(MemoryPreferencesStore())
                val manager = PromotionManager(settings)
                val game = Files.writeString(root.resolve("game"), "retained")

                val error = shouldThrow<IOException> { manager.demote(root, "game") }
                val result = PromotionInventory.inspect(root)

                assertSoftly {
                    error.message.isNullOrBlank() shouldBe false
                    result.status shouldBe DestinationStatus.MISSING_INDEX
                    settings.promotionFolders.first() shouldBe emptySet()
                    Files.list(root).use { it.toList() } shouldBe listOf(game)
                    Files.readString(game) shouldBe "retained"
                }
            }
        }

        @Test
        fun `on index write failure no transfer begins`() = withTestDirectory { root ->
            runBlocking {
                val folder = Files.createDirectory(root.resolve("games"))
                val original = Files.writeString(root.resolve("game.rom"), "data")
                val settings = DataStoreAppSettings(MemoryPreferencesStore())
                PromotionManager(settings).registerDirectory(folder)
                var writes = 0
                val manager = PromotionManager(settings, GameFileOperations(
                    createLink = { link, _ -> Files.writeString(link, "probe") },
                ), PromotionIndex { _, _ -> writes++; throw IOException("disk full") })

                val error = shouldThrow<IOException> { manager.promote(original, folder) }
                val result = manager.inventory()

                assertSoftly {
                    error.message.isNullOrBlank() shouldBe false
                    writes shouldBe 1
                    Files.readString(original) shouldBe "data"
                    Files.exists(folder.resolve("game.rom")) shouldBe false
                    result.single().promotions shouldBe emptyList()
                }
            }
        }

        @Test
        fun `on link preflight failure no promotion is indexed and another attempt remains possible`() = withTestDirectory { root ->
            runBlocking {
                val folder = Files.createDirectory(root.resolve("games"))
                val original = Files.writeString(root.resolve("game.rom"), "data")
                val settings = DataStoreAppSettings(MemoryPreferencesStore())
                var attempts = 0
                val manager = PromotionManager(settings, GameFileOperations(createLink = { _, _ ->
                    attempts++
                    throw IOException("required privilege is not held")
                }), PromotionIndex())

                val first = shouldThrow<IOException> { manager.promote(original, folder) }
                val second = shouldThrow<IOException> { manager.promote(original, folder) }
                val result = manager.inventory()

                assertSoftly {
                    first.message.orEmpty() shouldContain "preflight"
                    second.message.orEmpty() shouldContain "preflight"
                    attempts shouldBe 2
                    result.single().promotions shouldBe emptyList()
                    Files.readString(original) shouldBe "data"
                    Files.exists(folder.resolve("game.rom")) shouldBe false
                }
            }
        }

        @Test
        fun `on preflight cleanup failure the probe is reported without indexing a transfer`() = withTestDirectory { root ->
            runBlocking {
                val folder = Files.createDirectory(root.resolve("games"))
                val original = Files.writeString(root.resolve("game.rom"), "data")
                val settings = DataStoreAppSettings(MemoryPreferencesStore())
                var probe: java.nio.file.Path? = null
                val manager = PromotionManager(settings, GameFileOperations(
                    createLink = { link, _ -> probe = Files.writeString(link, "probe") },
                    delete = { throw IOException("probe is locked") },
                ), PromotionIndex())

                val error = shouldThrow<IOException> { manager.promote(original, folder) }
                val result = manager.inventory()

                assertSoftly {
                    error.message.orEmpty() shouldContain "preflight cleanup"
                    error.message.orEmpty() shouldContain probe.toString()
                    result.single().promotions shouldBe emptyList()
                    Files.readString(probe!!) shouldBe "probe"
                    Files.readString(original) shouldBe "data"
                    Files.exists(folder.resolve("game.rom")) shouldBe false
                }
            }
        }

        @Test
        fun `on transfer failure persisted intent remains visible after reopening`() = withTestDirectory { root ->
            runBlocking {
                val folder = Files.createDirectory(root.resolve("games"))
                val original = Files.writeString(root.resolve("game.rom"), "data")
                val settings = DataStoreAppSettings(MemoryPreferencesStore())
                var intentAtFailure: List<IndexedPromotion>? = null
                val manager = PromotionManager(settings, GameFileOperations(
                    createLink = { link, _ -> Files.writeString(link, "probe") },
                    move = { _, destination ->
                        intentAtFailure = PromotionIndex().read(folder)
                        Files.writeString(destination, "partial")
                        throw IOException("move failed")
                    },
                ), PromotionIndex())

                val error = shouldThrow<IOException> { manager.promote(original, folder) }
                val result = PromotionManager(settings).inventory().single().promotions.single()

                assertSoftly {
                    error.message.isNullOrBlank() shouldBe false
                    intentAtFailure shouldBe listOf(IndexedPromotion("game.rom", original, TransferState.PROMOTING))
                    result.state shouldBe TransferState.PROMOTING
                    result.condition shouldBe PromotionCondition.NEEDS_ATTENTION
                    Files.readString(original) shouldBe "data"
                    Files.readString(folder.resolve("game.rom")) shouldBe "partial"
                }
            }
        }

        @Test
        fun `on collisions and reserved metadata names existing content is preserved`() = withTestDirectory { root ->
            runBlocking {
                val folder = Files.createDirectory(root.resolve("games"))
                val original = Files.writeString(root.resolve("game"), "source")
                val occupied = Files.writeString(folder.resolve("game"), "retained")
                val metadata = Files.writeString(root.resolve(".gametier.json"), "source metadata")
                val manager = PromotionManager(DataStoreAppSettings(MemoryPreferencesStore()))
                manager.registerDirectory(folder)

                val collision = shouldThrow<IOException> { manager.promote(original, folder) }
                val reserved = shouldThrow<IOException> { manager.promote(metadata, folder) }
                val result = manager.inventory()

                assertSoftly {
                    collision.message.isNullOrBlank() shouldBe false
                    reserved.message.isNullOrBlank() shouldBe false
                    Files.readString(original) shouldBe "source"
                    Files.readString(occupied) shouldBe "retained"
                    Files.readString(metadata) shouldBe "source metadata"
                    result.single().promotions shouldBe emptyList()
                }
            }
        }

        @Test
        fun `on invalid demotion names no paths outside the destination are touched`() = withTestDirectory { root ->
            runBlocking {
                val manager = PromotionManager(DataStoreAppSettings(MemoryPreferencesStore()))
                manager.registerDirectory(root)

                listOf("../outside", ".gametier.json", "missing", "nested/game").forEach { name ->
                    val error = shouldThrow<IOException> { manager.demote(root, name) }
                    val result = manager.inventory()

                    assertSoftly {
                        error.message.isNullOrBlank() shouldBe false
                        result.single().promotions shouldBe emptyList()
                    }
                }
            }
        }
    }

    @Nested
    @Tag("symlink-success")
    inner class Transfers {
        @Test
        fun `on concurrent promotions through different managers neither index entry is lost`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            runBlocking {
                val folder = Files.createDirectory(root.resolve("games"))
                val originals = List(2) { Files.writeString(root.resolve("game-$it"), "data-$it") }
                val settings = DataStoreAppSettings(MemoryPreferencesStore())

                val promoted = coroutineScope {
                    originals.map { original -> async { PromotionManager(settings).promote(original, folder) } }.awaitAll()
                }
                val result = PromotionManager(settings).inventory().single()

                assertSoftly {
                    promoted shouldBe List(2) { Unit }
                    result.promotions.map { it.original }.toSet() shouldBe originals.toSet()
                    result.promotions.map { it.condition } shouldBe List(2) { PromotionCondition.PROMOTED }
                    originals.map { Files.readString(it) } shouldBe listOf("data-0", "data-1")
                }
            }
        }

        @Test
        fun `on demotion failure the original link and pending record remain`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            runBlocking {
                val folder = Files.createDirectory(root.resolve("games"))
                val original = Files.writeString(root.resolve("game"), "data")
                val settings = DataStoreAppSettings(MemoryPreferencesStore())
                PromotionManager(settings).promote(original, folder)
                var intentAtFailure: TransferState? = null
                val manager = PromotionManager(settings, GameFileOperations(move = { _, _ ->
                    intentAtFailure = PromotionIndex().read(folder).single().state
                    throw IOException("move failed")
                }), PromotionIndex())

                val error = shouldThrow<IOException> { manager.demote(folder, "game") }
                val result = PromotionManager(settings).inventory().single().promotions.single()

                assertSoftly {
                    error.message.isNullOrBlank() shouldBe false
                    intentAtFailure shouldBe TransferState.DEMOTING
                    result.state shouldBe TransferState.DEMOTING
                    result.condition shouldBe PromotionCondition.NEEDS_ATTENTION
                    Files.isSymbolicLink(original) shouldBe true
                    Files.readString(original) shouldBe "data"
                }
            }
        }

        @Test
        fun `on demotion completion write failure the returned data and pending record survive`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            runBlocking {
                val folder = Files.createDirectory(root.resolve("games"))
                val original = Files.writeString(root.resolve("game"), "data")
                val settings = DataStoreAppSettings(MemoryPreferencesStore())
                PromotionManager(settings).promote(original, folder)
                var writes = 0
                val manager = PromotionManager(settings, GameFileOperations(), PromotionIndex { temporary, file ->
                    if (++writes == 2) throw IOException("completion failed")
                    replacePromotionIndex(temporary, file)
                })

                val error = shouldThrow<IOException> { manager.demote(folder, "game") }
                val result = PromotionManager(settings).inventory().single().promotions.single()

                assertSoftly {
                    error.message.isNullOrBlank() shouldBe false
                    result.state shouldBe TransferState.DEMOTING
                    result.condition shouldBe PromotionCondition.NEEDS_ATTENTION
                    Files.isSymbolicLink(original) shouldBe false
                    Files.readString(original) shouldBe "data"
                    Files.exists(folder.resolve("game")) shouldBe false
                }
            }
        }

        @Test
        fun `on promotion and demotion the index tracks the actual relationship`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            runBlocking {
                val folder = Files.createDirectory(root.resolve("games"))
                val original = Files.writeString(root.resolve("game.rom"), "data")
                val manager = PromotionManager(DataStoreAppSettings(MemoryPreferencesStore()))

                val promoted = manager.promote(original, folder)
                val promotion = manager.inventory().single().promotions.single()
                val demoted = manager.demote(folder, "game.rom")
                val result = manager.inventory().single()

                assertSoftly {
                    promoted shouldBe Unit
                    promotion.condition shouldBe PromotionCondition.PROMOTED
                    promotion.original shouldBe original
                    demoted shouldBe Unit
                    result.promotions shouldBe emptyList()
                    result.unrecognized shouldBe emptyList()
                    Files.isSymbolicLink(original) shouldBe false
                    Files.readString(original) shouldBe "data"
                }
            }
        }

        @Test
        fun `on redirected origin demotion refuses to move unrelated data`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            runBlocking {
                val folder = Files.createDirectory(root.resolve("games"))
                val original = Files.writeString(root.resolve("game"), "game data")
                val other = Files.writeString(root.resolve("other"), "unrelated")
                val manager = PromotionManager(DataStoreAppSettings(MemoryPreferencesStore()))
                manager.promote(original, folder)
                Files.delete(original)
                Files.createSymbolicLink(original, other)

                val error = shouldThrow<IOException> { manager.demote(folder, "game") }
                val result = manager.inventory().single().promotions.single()

                assertSoftly {
                    error.message.isNullOrBlank() shouldBe false
                    result.condition shouldBe PromotionCondition.NEEDS_ATTENTION
                    result.state shouldBe TransferState.PROMOTED
                    Files.readString(other) shouldBe "unrelated"
                    Files.readString(folder.resolve("game")) shouldBe "game data"
                }
            }
        }

        @Test
        fun `on promotion finalization failure the valid link retains pending intent`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            runBlocking {
                val folder = Files.createDirectory(root.resolve("games"))
                val original = Files.writeString(root.resolve("game"), "data")
                val settings = DataStoreAppSettings(MemoryPreferencesStore())
                PromotionManager(settings).registerDirectory(folder)
                var writes = 0
                val manager = PromotionManager(settings, GameFileOperations(), PromotionIndex { temporary, file ->
                    if (++writes == 2) throw IOException("completion failed")
                    replacePromotionIndex(temporary, file)
                })

                val error = shouldThrow<IOException> { manager.promote(original, folder) }
                val result = PromotionManager(settings).inventory().single().promotions.single()

                assertSoftly {
                    error.message.isNullOrBlank() shouldBe false
                    result.state shouldBe TransferState.PROMOTING
                    result.condition shouldBe PromotionCondition.NEEDS_ATTENTION
                    Files.isSymbolicLink(original) shouldBe true
                    Files.readString(original) shouldBe "data"
                }
            }
        }
    }
}
