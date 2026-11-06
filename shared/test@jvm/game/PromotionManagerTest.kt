package com.bkahlert.gametier.game

import com.bkahlert.gametier.settings.DataStoreAppSettings
import com.bkahlert.gametier.files.TransferProgress
import com.bkahlert.gametier.files.TransferPhase
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
    @Tag("symlink-success")
    inner class ProtectedTransfers {
        @Test
        fun `on an occupant appearing during restore its data is preserved`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            runBlocking {
                val original = Files.writeString(root.resolve("game"), "snapshot")
                val directory = Files.createDirectory(root.resolve("games"))
                val manager = isolatedPromotionManager(root)
                manager.promote(original, directory)
                Files.delete(directory.resolve("game"))

                val result = shouldThrow<IOException> {
                    manager.restoreBackup(original) {
                        if (it.phase == TransferPhase.FINALIZING) {
                            Files.delete(original)
                            Files.writeString(original, "new occupant")
                        }
                    }
                }

                assertSoftly {
                    result.message.orEmpty() shouldContain "occupied"
                    Files.readString(original) shouldBe "new occupant"
                    Files.readString(root.resolve("backups/game/data")) shouldBe "snapshot"
                }
            }
        }

        @Test
        fun `on a redirected original parent demotion preserves both locations`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            runBlocking {
                val parent = Files.createDirectory(root.resolve("original"))
                val original = Files.writeString(parent.resolve("game"), "snapshot")
                val directory = Files.createDirectory(root.resolve("games"))
                val manager = isolatedPromotionManager(root)
                manager.promote(original, directory)
                Files.move(parent, root.resolve("saved-original"))
                val outside = Files.createDirectory(root.resolve("outside"))
                Files.createSymbolicLink(outside.resolve("game"), directory.resolve("game"))
                createTestJunction(parent, outside)

                val result = shouldThrow<IOException> { manager.demote(directory, "game") }

                assertSoftly {
                    result.message.orEmpty() shouldContain "ordinary directory"
                    Files.isSymbolicLink(outside.resolve("game")) shouldBe true
                    Files.readString(directory.resolve("game")) shouldBe "snapshot"
                    Files.readString(root.resolve("backups/game/data")) shouldBe "snapshot"
                }
            }
        }

        @Test
        fun `on failed demotion installation latest files and snapshot remain recoverable`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            runBlocking {
                val original = Files.writeString(root.resolve("game"), "snapshot")
                val directory = Files.createDirectory(root.resolve("games"))
                isolatedPromotionManager(root).promote(original, directory)
                Files.writeString(directory.resolve("game"), "latest")
                val manager = isolatedPromotionManager(root, operations = GameFileOperations(
                    move = { source, destination ->
                        if (source.fileName.toString() == "return") throw IOException("install failed")
                        com.bkahlert.gametier.files.FileMover.move(source, destination)
                    },
                ))

                val result = shouldThrow<IOException> { manager.demote(directory, "game") }

                assertSoftly {
                    result.message.orEmpty() shouldContain "install failed"
                    Files.isSymbolicLink(original) shouldBe true
                    Files.readString(original) shouldBe "latest"
                    Files.readString(root.resolve("backups/game/return")) shouldBe "latest"
                    Files.readString(root.resolve("backups/game/data")) shouldBe "snapshot"
                }
            }
        }

        @Test
        fun `on supplied backup type mismatch adoption preserves the supplied and active files`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            runBlocking {
                val original = root.resolve("game")
                val directory = Files.createDirectory(root.resolve("games"))
                Files.writeString(directory.resolve("game"), "active file")
                Files.createSymbolicLink(original, directory.resolve("game"))
                PromotionIndex().write(directory, listOf(IndexedPromotion("game", original, TransferState.PROMOTED)))
                val supplied = Files.createDirectory(root.resolve("supplied"))

                val result = shouldThrow<IOException> { isolatedPromotionManager(root).adoptBackup(original, supplied) }

                assertSoftly {
                    result.message.orEmpty() shouldContain "type"
                    Files.isDirectory(supplied) shouldBe true
                    Files.readString(original) shouldBe "active file"
                    Files.exists(root.resolve("backups/game")) shouldBe false
                }
            }
        }

        @Test
        fun `on promotion a flat snapshot and manual recovery metadata retain original data`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            runBlocking {
                val original = Files.writeString(root.resolve("game.rom"), "snapshot")
                val target = Files.createDirectory(root.resolve("games"))
                val manager = isolatedPromotionManager(root)

                manager.promote(original, target)
                val promotion = manager.inventory().single().promotions.single()

                assertSoftly {
                    promotion.backup shouldBe root.resolve("backups/game.rom/data")
                    Files.readString(promotion.backup!!) shouldBe "snapshot"
                    Files.readString(root.resolve("backups/game.rom/backup.json")) shouldContain original.toString().replace("\\", "\\\\")
                    Files.readString(root.resolve("backups/game.rom/RESTORE.txt")) shouldContain "data"
                    promotion.canRestoreBackup shouldBe false
                }
            }
        }

        @Test
        fun `on changed target demotion returns latest data and consumes snapshot`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            runBlocking {
                val original = Files.writeString(root.resolve("game"), "snapshot")
                val target = Files.createDirectory(root.resolve("games"))
                val manager = isolatedPromotionManager(root)
                manager.promote(original, target)
                Files.writeString(target.resolve("game"), "latest")

                manager.demote(target, "game")
                val result = manager.inventory().single()

                assertSoftly {
                    Files.readString(original) shouldBe "latest"
                    Files.exists(target.resolve("game")) shouldBe false
                    Files.exists(root.resolve("backups/game")) shouldBe false
                    result.promotions shouldBe emptyList()
                }
            }
        }

        @Test
        fun `on missing destination and settings source metadata alone discovers and restores backup`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            runBlocking {
                val original = Files.writeString(root.resolve("game"), "snapshot")
                val target = Files.createDirectory(root.resolve("games"))
                isolatedPromotionManager(root).promote(original, target)
                Files.delete(target.resolve("game"))
                Files.delete(target.resolve(".gametier.json"))
                Files.delete(target)
                val reopened = isolatedPromotionManager(root)

                val before = reopened.inventory().single().promotions.single()
                reopened.restoreBackup(original)
                val after = reopened.inventory()

                assertSoftly {
                    before.canRestoreBackup shouldBe true
                    Files.isSymbolicLink(original) shouldBe false
                    Files.readString(original) shouldBe "snapshot"
                    Files.exists(root.resolve("backups/game")) shouldBe false
                    after shouldBe emptyList()
                }
            }
        }

        @Test
        fun `on an existing flat name promotion refuses without replacing any content`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            runBlocking {
                val first = Files.writeString(root.resolve("game"), "first")
                val second = Files.createDirectories(root.resolve("other")).resolve("game")
                Files.writeString(second, "second")
                val target = Files.createDirectory(root.resolve("games"))
                val otherTarget = Files.createDirectory(root.resolve("other-games"))
                val manager = isolatedPromotionManager(root)
                manager.promote(first, target)

                val failure = shouldThrow<IOException> { manager.promote(second, otherTarget) }

                assertSoftly {
                    failure.message.orEmpty() shouldContain "backup"
                    Files.readString(second) shouldBe "second"
                    Files.readString(root.resolve("backups/game/data")) shouldBe "first"
                    Files.exists(otherTarget.resolve("game")) shouldBe false
                }
            }
        }

        @Test
        fun `on restore live targets occupants and redirected links are refused`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            runBlocking {
                val original = Files.writeString(root.resolve("game"), "snapshot")
                val target = Files.createDirectory(root.resolve("games"))
                val manager = isolatedPromotionManager(root)
                manager.promote(original, target)
                val live = shouldThrow<IOException> { manager.restoreBackup(original) }
                Files.delete(target.resolve("game"))
                Files.delete(original)
                Files.writeString(original, "occupant")
                val occupied = shouldThrow<IOException> { manager.restoreBackup(original) }
                Files.delete(original)
                val other = Files.writeString(root.resolve("other"), "unrelated")
                Files.createSymbolicLink(original, other)
                val redirected = shouldThrow<IOException> { manager.restoreBackup(original) }

                assertSoftly {
                    listOf(live, occupied, redirected).all { !it.message.isNullOrBlank() } shouldBe true
                    Files.readString(other) shouldBe "unrelated"
                    Files.readString(root.resolve("backups/game/data")) shouldBe "snapshot"
                }
            }
        }

        @Test
        fun `on trusted adoption supplied snapshot is retained without comparing active content`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            runBlocking {
                val original = root.resolve("game")
                val target = Files.createDirectory(root.resolve("games"))
                Files.writeString(target.resolve("game"), "changed active data")
                Files.createSymbolicLink(original, target.resolve("game"))
                PromotionIndex().write(target, listOf(IndexedPromotion("game", original, TransferState.PROMOTED)))
                val supplied = Files.writeString(root.resolve("supplied"), "trusted snapshot")
                val manager = isolatedPromotionManager(root)
                manager.registerDirectory(target)

                manager.adoptBackup(original, supplied)
                val result = manager.inventory().single().promotions.single()

                assertSoftly {
                    Files.readString(result.backup!!) shouldBe "trusted snapshot"
                    Files.readString(original) shouldBe "changed active data"
                    Files.exists(supplied) shouldBe false
                }
            }
        }
    }


    @Nested
    inner class SourceBackups {
        @Test
        fun `on a backup payload destination promotion refuses before writing an index`() = withTestDirectory { root ->
            runBlocking {
                val original = Files.writeString(root.resolve("game"), "source")
                val payload = Files.createDirectories(root.resolve("backups/existing/data"))
                Files.writeString(payload.resolve("game.dat"), "snapshot")
                val manager = isolatedPromotionManager(root, operations = GameFileOperations(
                    createLink = { link, _ -> Files.writeString(link, "probe") },
                ))

                val result = shouldThrow<IOException> { manager.promote(original, payload) }

                assertSoftly {
                    result.message.orEmpty() shouldContain "backup storage"
                    Files.readString(original) shouldBe "source"
                    Files.readString(payload.resolve("game.dat")) shouldBe "snapshot"
                    Files.exists(payload.resolve(".gametier.json")) shouldBe false
                }
            }
        }

        @Test
        fun `on registering backup storage no index is written`() = withTestDirectory { root ->
            runBlocking {
                val directory = Files.createDirectories(root.resolve("backups/existing"))

                val result = shouldThrow<IOException> { isolatedPromotionManager(root).registerDirectory(directory) }

                assertSoftly {
                    result.message.orEmpty() shouldContain "backup storage"
                    Files.list(directory).use { it.count() } shouldBe 0L
                }
            }
        }

        @Test
        fun `on a redirected destination directory source backup remains visible`() = withTestDirectory { root ->
            runBlocking {
                val fixture = sourceBackupFixture(root)
                Files.move(fixture.directory, root.resolve("old-games"))
                val replacement = Files.createDirectory(root.resolve("replacement"))
                createTestJunction(fixture.directory, replacement)
                val manager = isolatedPromotionManager(root)

                val result = manager.inventory().flatMap { it.promotions }.single()

                assertSoftly {
                    result.original shouldBe fixture.original
                    result.destination shouldBe fixture.target
                    result.backup shouldBe fixture.record.data
                    result.canRestoreBackup shouldBe true
                }
            }
        }

        @Test
        fun `on source replacement during failed relocation the verified target is retained`() = withTestDirectory { root ->
            runBlocking {
                val original = Files.writeString(root.resolve("game"), "snapshot")
                val directory = Files.createDirectory(root.resolve("games"))
                val manager = isolatedPromotionManager(root, operations = GameFileOperations(
                    createLink = { link, _ -> Files.writeString(link, "probe") },
                    move = { source, _ ->
                        Files.delete(source)
                        Files.writeString(source, "new occupant")
                        throw IOException("relocation interrupted")
                    },
                ))

                val result = shouldThrow<IOException> { manager.promote(original, directory) }

                assertSoftly {
                    result.message.orEmpty() shouldContain "relocation interrupted"
                    Files.readString(original) shouldBe "new occupant"
                    Files.exists(directory.resolve("game")) shouldBe true
                }
            }
        }

        @Test
        fun `on unreadable settings source backups remain discoverable and restorable`() = withTestDirectory { root ->
            runBlocking {
                val fixture = sourceBackupFixture(root)
                val settings = DataStoreAppSettings(MemoryPreferencesStore().apply { readFailure = IOException("unreadable settings") })
                val manager = isolatedPromotionManager(root, settings)

                val result = manager.inventory().single().promotions.single()
                manager.restoreBackup(fixture.original)

                assertSoftly {
                    result.canRestoreBackup shouldBe true
                    result.backup shouldBe fixture.record.data
                    Files.readString(fixture.original) shouldBe "snapshot"
                    Files.exists(fixture.record.folder) shouldBe false
                }
            }
        }

        @Test
        fun `on interrupted demotion staging restore preserves the latest surviving version`() = withTestDirectory { root ->
            runBlocking {
                val fixture = sourceBackupFixture(root)
                Files.writeString(fixture.record.staging, "latest")
                fixture.store.write(fixture.record.copy(state = TransferState.DEMOTING))
                val manager = isolatedPromotionManager(root)

                val result = shouldThrow<IOException> { manager.restoreBackup(fixture.original) }
                val promotion = manager.inventory().single().promotions.single()

                assertSoftly {
                    result.message.orEmpty() shouldContain "staging"
                    promotion.canRestoreBackup shouldBe false
                    Files.exists(fixture.original) shouldBe false
                    Files.readString(fixture.record.data) shouldBe "snapshot"
                    Files.readString(fixture.record.staging) shouldBe "latest"
                }
            }
        }

        @Test
        fun `on absent original recovery consumes snapshot despite corrupt target index and unavailable settings writes`() = withTestDirectory { root ->
            runBlocking {
                val fixture = sourceBackupFixture(root)
                Files.writeString(fixture.directory.resolve(".gametier.json"), "corrupt metadata")
                val settings = DataStoreAppSettings(MemoryPreferencesStore().apply { writeFailure = IOException("settings unavailable") })
                val manager = isolatedPromotionManager(root, settings)

                val before = manager.inventory().single()
                manager.restoreBackup(fixture.original)

                assertSoftly {
                    before.status shouldBe DestinationStatus.INVALID_INDEX
                    before.promotions.single().canRestoreBackup shouldBe true
                    Files.readString(fixture.original) shouldBe "snapshot"
                    Files.exists(fixture.record.folder) shouldBe false
                    Files.readString(fixture.directory.resolve(".gametier.json")) shouldBe "corrupt metadata"
                }
            }
        }

        @Test
        fun `on source interrupted state a completed target index still needs attention`() = withTestDirectory { root ->
            runBlocking {
                val fixture = sourceBackupFixture(root)
                PromotionIndex().write(fixture.directory, listOf(IndexedPromotion("game", fixture.original, TransferState.PROMOTED)))
                val settings = DataStoreAppSettings(MemoryPreferencesStore())
                settings.rememberPromotionFolder(fixture.directory.toString())

                val result = isolatedPromotionManager(root, settings).inventory().single().promotions.single()

                assertSoftly {
                    result.state shouldBe TransferState.PROMOTING
                    result.condition shouldBe PromotionCondition.NEEDS_ATTENTION
                    result.backup shouldBe fixture.record.data
                    result.problem.orEmpty() shouldContain "Interrupted"
                }
            }
        }

        @Test
        fun `on malformed source metadata an indexed promotion exposes its backup problem`() = withTestDirectory { root ->
            runBlocking {
                val fixture = sourceBackupFixture(root)
                PromotionIndex().write(fixture.directory, listOf(IndexedPromotion("game", fixture.original, TransferState.PROMOTED)))
                Files.writeString(fixture.record.folder.resolve("backup.json"), "corrupt source metadata")
                val settings = DataStoreAppSettings(MemoryPreferencesStore())
                settings.rememberPromotionFolder(fixture.directory.toString())
                val manager = isolatedPromotionManager(root, settings)

                val result = manager.inventory().single().promotions.single()
                val failure = shouldThrow<Exception> { manager.restoreBackup(fixture.original) }

                assertSoftly {
                    result.condition shouldBe PromotionCondition.NEEDS_ATTENTION
                    result.backup shouldBe fixture.record.data
                    result.canRestoreBackup shouldBe false
                    result.problem.orEmpty() shouldContain "source backup"
                    failure.message.isNullOrBlank() shouldBe false
                    Files.readString(fixture.record.data) shouldBe "snapshot"
                }
            }
        }

        @Test
        fun `on failed restore installation payload and source metadata remain independently discoverable`() = withTestDirectory { root ->
            runBlocking {
                val fixture = sourceBackupFixture(root)
                val failure = IOException("installation failed")
                val manager = isolatedPromotionManager(root, operations = GameFileOperations(move = { _, _ -> throw failure }))

                val error = shouldThrow<IOException> { manager.restoreBackup(fixture.original) }
                val result = isolatedPromotionManager(root).inventory().single().promotions.single()

                assertSoftly {
                    generateSequence(error as Throwable) { it.cause }.last() shouldBe failure
                    result.canRestoreBackup shouldBe true
                    Files.readString(fixture.record.data) shouldBe "snapshot"
                    Files.exists(fixture.original) shouldBe false
                }
            }
        }

        @Test
        fun `on ordinary original occupant and live target restore leaves all data untouched`() = withTestDirectory { root ->
            runBlocking {
                val fixture = sourceBackupFixture(root)
                val manager = isolatedPromotionManager(root)
                Files.writeString(fixture.original, "occupant")
                val occupied = shouldThrow<IOException> { manager.restoreBackup(fixture.original) }
                Files.delete(fixture.original)
                Files.writeString(fixture.target, "latest")
                val live = shouldThrow<IOException> { manager.restoreBackup(fixture.original) }

                assertSoftly {
                    occupied.message.orEmpty() shouldContain "occupied"
                    live.message.orEmpty() shouldContain "still exists"
                    Files.readString(fixture.target) shouldBe "latest"
                    Files.readString(fixture.record.data) shouldBe "snapshot"
                }
            }
        }

        @Test
        fun `on discovery backup payload descendants are not followed`() = withTestDirectory { root ->
            runBlocking {
                val fixture = sourceBackupFixture(root)
                Files.delete(fixture.record.data)
                Files.createDirectory(fixture.record.data)
                createTestJunction(fixture.record.data.resolve("cycle"), fixture.record.data)

                val result = isolatedPromotionManager(root).inventory().single().promotions.single()

                assertSoftly {
                    result.backup shouldBe fixture.record.data
                    result.canRestoreBackup shouldBe true
                }
            }
        }

        @Test
        fun `on snapshot relocation failure original remains and the verified target is cleaned up`() = withTestDirectory { root ->
            runBlocking {
                val original = Files.writeString(root.resolve("game"), "original")
                val directory = Files.createDirectory(root.resolve("games"))
                val manager = isolatedPromotionManager(root, operations = GameFileOperations(
                    createLink = { link, _ -> Files.writeString(link, "probe") },
                    move = { _, _ -> throw IOException("snapshot rename failed") },
                ))

                val failure = shouldThrow<IOException> { manager.promote(original, directory) }
                val result = manager.inventory().single().promotions.single()

                assertSoftly {
                    failure.message.orEmpty() shouldContain "snapshot relocation"
                    Files.readString(original) shouldBe "original"
                    Files.exists(directory.resolve("game")) shouldBe false
                    result.state shouldBe TransferState.PROMOTING
                    result.backup shouldBe null
                }
            }
        }

        @Test
        fun `on failed final link a new original occupant never gets overwritten`() = withTestDirectory { root ->
            runBlocking {
                val original = Files.writeString(root.resolve("game"), "snapshot")
                val directory = Files.createDirectory(root.resolve("games"))
                val manager = isolatedPromotionManager(root, operations = GameFileOperations(
                    createLink = { link, _ ->
                        if (link == original) {
                            Files.writeString(link, "new occupant")
                            throw IOException("link collision")
                        } else Files.writeString(link, "probe")
                    },
                ))

                val failure = shouldThrow<IOException> { manager.promote(original, directory) }
                val result = manager.inventory().single().promotions.single()

                assertSoftly {
                    failure.message.orEmpty() shouldContain "link creation"
                    Files.readString(original) shouldBe "new occupant"
                    Files.readString(directory.resolve("game")) shouldBe "snapshot"
                    Files.readString(result.backup!!) shouldBe "snapshot"
                    result.canRestoreBackup shouldBe false
                }
            }
        }
    }


    @Nested
    inner class Progress {
        @Test
        fun `on a copy finalizing observer failure the original and pending intent remain`() = withTestDirectory { root ->
            runBlocking {
                val original = Files.writeString(root.resolve("game"), "data")
                val folder = Files.createDirectory(root.resolve("games"))
                val failure = IOException("finalizing observer failed")
                val manager = isolatedPromotionManager(root, DataStoreAppSettings(MemoryPreferencesStore()), GameFileOperations(
                    createLink = { link, _ -> Files.writeString(link, "probe") },
                ), PromotionIndex())

                val result = shouldThrow<IOException> {
                    manager.promote(original, folder) {
                        if (it.phase == TransferPhase.FINALIZING) throw failure
                    }
                }

                assertSoftly {
                    generateSequence(result as Throwable) { it.cause }.last() shouldBe failure
                    PromotionIndex().read(folder).single().state shouldBe TransferState.PROMOTING
                    Files.exists(original) shouldBe true
                    Files.readString(original) shouldBe "data"
                    Files.readString(folder.resolve("game")) shouldBe "data"
                }
            }
        }

        @Test
        fun `on promotion progress reaches finalizing before metadata completes`() = withTestDirectory { root ->
            runBlocking {
                val original = Files.writeString(root.resolve("game"), "data")
                val folder = Files.createDirectory(root.resolve("games"))
                val updates = mutableListOf<TransferProgress>()
                val manager = isolatedPromotionManager(root, DataStoreAppSettings(MemoryPreferencesStore()), GameFileOperations(
                    createLink = { link, _ -> Files.writeString(link, "link") },
                ), PromotionIndex())

                val result = manager.promote(original, folder, updates::add)

                assertSoftly {
                    result shouldBe Unit
                    updates.first().phase shouldBe TransferPhase.PREPARING
                    updates.any { it.phase == TransferPhase.MOVING } shouldBe true
                    updates.last() shouldBe TransferProgress(TransferPhase.FINALIZING)
                    updates.none { it.recovering } shouldBe true
                    PromotionIndex().read(folder).single().state shouldBe TransferState.PROMOTED
                }
            }
        }

        @Test
        fun `on final link failure recovery resets progress and retains the original error`() = withTestDirectory { root ->
            runBlocking {
                val original = Files.writeString(root.resolve("game"), "data")
                val folder = Files.createDirectory(root.resolve("games"))
                val updates = mutableListOf<TransferProgress>()
                val failure = IOException("final link failed")
                val manager = isolatedPromotionManager(root, DataStoreAppSettings(MemoryPreferencesStore()), GameFileOperations(
                    createLink = { link, _ -> if (link == original) throw failure else Files.writeString(link, "probe") },
                ), PromotionIndex())

                val result = shouldThrow<IOException> { manager.promote(original, folder, updates::add) }

                assertSoftly {
                    generateSequence(result as Throwable) { it.cause }.last() shouldBe failure
                    val recovery = updates.filter { it.recovering }
                    recovery.first() shouldBe TransferProgress(TransferPhase.PREPARING, recovering = true)
                    recovery.last() shouldBe TransferProgress(TransferPhase.FINALIZING, recovering = true)
                    Files.readString(original) shouldBe "data"
                    Files.exists(folder.resolve("game")) shouldBe false
                }
            }
        }

        @Test
        fun `on demotion validation failure only preparing is reported`() = withTestDirectory { root ->
            runBlocking {
                val updates = mutableListOf<TransferProgress>()
                val manager = isolatedPromotionManager(root, DataStoreAppSettings(MemoryPreferencesStore()))

                val result = shouldThrow<IOException> { manager.demote(root, "missing", updates::add) }

                assertSoftly {
                    result.message.isNullOrBlank() shouldBe false
                    updates shouldBe listOf(TransferProgress(TransferPhase.PREPARING))
                }
            }
        }
    }

    @Nested
    inner class Registration {
        @Test
        fun `on default parent aliases inventory reports each physical directory once`() = withTestDirectory { root ->
            runBlocking {
                val settings = DataStoreAppSettings(MemoryPreferencesStore())
                val folder = Files.createDirectory(root.resolve("games"))
                val alias = root.resolve("alias")
                createTestJunction(alias, folder)
                val manager = isolatedPromotionManager(root, settings)
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
                val manager = isolatedPromotionManager(root, settings)
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
                val manager = isolatedPromotionManager(root, settings)
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
                val manager = isolatedPromotionManager(root, settings)
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

                val result = isolatedPromotionManager(root, settings).inventory()

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
                    folders.map { folder -> async(Dispatchers.Default) { isolatedPromotionManager(root, settings).registerDirectory(folder) } }.awaitAll()
                }
                val result = isolatedPromotionManager(root, settings).inventory()

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
                val manager = isolatedPromotionManager(root, settings)

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

                val error = shouldThrow<IOException> { isolatedPromotionManager(root, settings).demote(root, "game") }
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
                val manager = isolatedPromotionManager(root, settings)
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
                isolatedPromotionManager(root, settings).registerDirectory(folder)
                var writes = 0
                val manager = isolatedPromotionManager(root, settings, GameFileOperations(
                    createLink = { link, _ -> Files.writeString(link, "probe") },
                ), PromotionIndex { _, _ -> writes++; throw IOException("disk full") })

                val error = shouldThrow<IOException> { manager.promote(original, folder) }
                val result = manager.inventory()

                assertSoftly {
                    error.message.isNullOrBlank() shouldBe false
                    writes shouldBe 1
                    Files.readString(original) shouldBe "data"
                    Files.exists(folder.resolve("game.rom")) shouldBe false
                    result.single().promotions.single().state shouldBe TransferState.PROMOTING
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
                val manager = isolatedPromotionManager(root, settings, GameFileOperations(createLink = { _, _ ->
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
                val manager = isolatedPromotionManager(root, settings, GameFileOperations(
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
                val manager = isolatedPromotionManager(root, settings, GameFileOperations(
                    createLink = { link, _ -> Files.writeString(link, "probe") },
                    copy = { _, destination, _ ->
                        intentAtFailure = PromotionIndex().read(folder)
                        Files.writeString(destination, "partial")
                        throw IOException("move failed")
                    },
                ), PromotionIndex())

                val error = shouldThrow<IOException> { manager.promote(original, folder) }
                val result = isolatedPromotionManager(root, settings).inventory().single().promotions.single()

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
                val manager = isolatedPromotionManager(root, DataStoreAppSettings(MemoryPreferencesStore()))
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
                val manager = isolatedPromotionManager(root, DataStoreAppSettings(MemoryPreferencesStore()))
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
                    originals.map { original -> async { isolatedPromotionManager(root, settings).promote(original, folder) } }.awaitAll()
                }
                val result = isolatedPromotionManager(root, settings).inventory().single()

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
                isolatedPromotionManager(root, settings).promote(original, folder)
                var intentAtFailure: TransferState? = null
                val manager = isolatedPromotionManager(root, settings, GameFileOperations(copy = { _, _, _ ->
                    intentAtFailure = PromotionIndex().read(folder).single().state
                    throw IOException("move failed")
                }), PromotionIndex())

                val error = shouldThrow<IOException> { manager.demote(folder, "game") }
                val result = isolatedPromotionManager(root, settings).inventory().single().promotions.single()

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
                isolatedPromotionManager(root, settings).promote(original, folder)
                var writes = 0
                val manager = isolatedPromotionManager(root, settings, GameFileOperations(), PromotionIndex { temporary, file ->
                    if (++writes == 2) throw IOException("completion failed")
                    replacePromotionIndex(temporary, file)
                })

                val error = shouldThrow<IOException> { manager.demote(folder, "game") }
                val result = isolatedPromotionManager(root, settings).inventory().single().promotions.single()

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
                val manager = isolatedPromotionManager(root, DataStoreAppSettings(MemoryPreferencesStore()))

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
                val manager = isolatedPromotionManager(root, DataStoreAppSettings(MemoryPreferencesStore()))
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
                isolatedPromotionManager(root, settings).registerDirectory(folder)
                var writes = 0
                val manager = isolatedPromotionManager(root, settings, GameFileOperations(), PromotionIndex { temporary, file ->
                    if (++writes == 2) throw IOException("completion failed")
                    replacePromotionIndex(temporary, file)
                })

                val error = shouldThrow<IOException> { manager.promote(original, folder) }
                val result = isolatedPromotionManager(root, settings).inventory().single().promotions.single()

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
