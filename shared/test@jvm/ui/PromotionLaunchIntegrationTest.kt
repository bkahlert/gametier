package com.bkahlert.gametier.ui

import com.bkahlert.gametier.files.FileMover
import com.bkahlert.gametier.game.BackupStore
import com.bkahlert.gametier.game.GameFileOperations
import com.bkahlert.gametier.game.IndexedPromotion
import com.bkahlert.gametier.game.PromotionIndex
import com.bkahlert.gametier.game.PromotionManager
import com.bkahlert.gametier.game.TransferState
import com.bkahlert.gametier.game.sourceBackupFixture
import com.bkahlert.gametier.testsupport.filesystem.requireSymlinkSupport
import com.bkahlert.gametier.testsupport.filesystem.createTestJunction
import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag

@OptIn(ExperimentalCoroutinesApi::class)
class PromotionLaunchIntegrationTest {
    @Nested
    inner class SelectionValidation {
        @Test
        fun `on an unrelated directory alias recovery does not follow it into backup storage`() = withTestDirectory { root ->
            runTest {
                val fixture = sourceBackupFixture(root)
                fixture.store.write(fixture.record.copy(state = TransferState.PROMOTED))
                val alias = root.resolve("alias")
                createTestJunction(alias, fixture.record.folder)
                val settings = AppTestSettings(default = null)
                val manager = PromotionManager(settings, GameFileOperations(), PromotionIndex(), fixture.store)
                val controller = AppController(backgroundScope, settings, ManagerPromotionOperations(settings, manager),
                    readyPreparer, StandardTestDispatcher(testScheduler))

                PromotionLaunch.parse(arrayOf("--restore-backup", alias.resolve("data").toString()))!!.open(controller)
                val result = controller.state.first { it.backupRestoration != null || it.inputError != null }

                assertSoftly {
                    result.inputError shouldNotBe null
                    result.demotion shouldBe null
                    result.backupRestoration shouldBe null
                    result.activity shouldBe emptyList()
                    Files.readString(fixture.record.data) shouldBe "snapshot"
                }
            }
        }

        @Test
        @Tag("symlink-success")
        fun `on a redirected original descendant another managed game is never substituted`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            runTest {
                val fixture = promotedFixture(root)
                val otherOriginal = Files.createDirectory(root.resolve("other"))
                Files.writeString(Files.createDirectory(otherOriginal.resolve("nested")).resolve("save"), "other snapshot")
                selectionManager(root, fixture.settings).promote(otherOriginal, fixture.destination.parent)
                Files.delete(fixture.original)
                Files.createSymbolicLink(fixture.original, fixture.destination.parent.resolve("other"))
                val controller = AppController(backgroundScope, fixture.settings, fixture.operations, readyPreparer,
                    StandardTestDispatcher(testScheduler))

                PromotionLaunch.parse(arrayOf("--demote", fixture.original.resolve("nested/save").toString()))!!.open(controller)
                val result = controller.state.first { it.demotion != null || it.inputError != null }

                assertSoftly {
                    result.inputError shouldNotBe null
                    result.demotion shouldBe null
                    result.backupRestoration shouldBe null
                    result.activity shouldBe emptyList()
                    Files.readString(otherOriginal.resolve("nested/save")) shouldBe "other snapshot"
                }
            }
        }

        @Test
        @Tag("symlink-success")
        fun `on mismatched or stale requests neither action silently replaces the other`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            runTest {
                val fixture = promotedFixture(root)
                val selections = listOf("--restore-backup" to fixture.destination, "--demote" to root.resolve("unmanaged"),
                    "--demote" to root.resolve("removed"), "--demote" to root.resolve("alias"))
                Files.writeString(root.resolve("unmanaged"), "unmanaged")
                Files.createSymbolicLink(root.resolve("alias"), fixture.destination)
                selections.forEach { (argument, selection) ->
                    val controller = AppController(backgroundScope, fixture.settings, fixture.operations, readyPreparer,
                        StandardTestDispatcher(testScheduler))

                    PromotionLaunch.parse(arrayOf(argument, selection.toString()))!!.open(controller)
                    val result = controller.state.first { it.demotion != null || it.backupRestoration != null || it.inputError != null }

                    assertSoftly {
                        result.inputError shouldNotBe null
                        result.demotion shouldBe null
                        result.backupRestoration shouldBe null
                        result.activity shouldBe emptyList()
                    }
                }
                FileMover.deleteTree(fixture.destination)
                val controller = AppController(backgroundScope, fixture.settings, fixture.operations, readyPreparer,
                    StandardTestDispatcher(testScheduler))

                PromotionLaunch.parse(arrayOf("--demote", fixture.original.toString()))!!.open(controller)
                val result = controller.state.first { it.demotion != null || it.backupRestoration != null || it.inputError != null }

                assertSoftly {
                    result.inputError shouldNotBe null
                    result.demotion shouldBe null
                    result.backupRestoration shouldBe null
                }
            }
        }
    }

    @Nested
    inner class BackupRecovery {
        @Test
        fun `on unsafe recovery states the request reports a problem without moving data`() {
            listOf("interrupted", "staging", "snapshot missing", "occupied original", "live target", "invalid metadata").forEach { problem ->
                withTestDirectory { root ->
                    runTest {
                        val fixture = sourceBackupFixture(root)
                        fixture.store.write(fixture.record.copy(state = TransferState.PROMOTED))
                        when (problem) {
                            "interrupted" -> fixture.store.write(fixture.record.copy(state = TransferState.DEMOTING))
                            "staging" -> Files.createDirectory(fixture.record.staging)
                            "snapshot missing" -> Files.delete(fixture.record.data)
                            "occupied original" -> Files.writeString(fixture.original, "unrelated")
                            "live target" -> Files.writeString(fixture.target, "latest")
                            "invalid metadata" -> Files.writeString(fixture.record.folder.resolve("backup.json"), "malformed")
                        }
                        val settings = AppTestSettings(default = null)
                        val manager = PromotionManager(settings, GameFileOperations(), PromotionIndex(), fixture.store)
                        val controller = AppController(backgroundScope, settings, ManagerPromotionOperations(settings, manager),
                            readyPreparer, StandardTestDispatcher(testScheduler))

                        PromotionLaunch.parse(arrayOf("--restore-backup", fixture.record.folder.toString()))!!.open(controller)
                        val result = controller.state.first { it.backupRestoration != null || it.inputError != null }

                        assertSoftly {
                            result.inputError shouldNotBe null
                            result.demotion shouldBe null
                            result.backupRestoration shouldBe null
                            result.activity shouldBe emptyList()
                            if (problem != "snapshot missing") Files.readString(fixture.record.data) shouldBe "snapshot"
                            if (problem == "occupied original") Files.readString(fixture.original) shouldBe "unrelated"
                            if (problem == "live target") Files.readString(fixture.target) shouldBe "latest"
                        }
                    }
                }
            }
        }

        @Test
        fun `on backup-only recovery the snapshot waits for restore confirmation`() = withTestDirectory { root ->
            runTest {
                val fixture = sourceBackupFixture(root)
                fixture.store.write(fixture.record.copy(state = TransferState.PROMOTED))
                val settings = AppTestSettings(default = null)
                val manager = PromotionManager(settings, GameFileOperations(), PromotionIndex(), fixture.store)
                val controller = AppController(backgroundScope, settings, ManagerPromotionOperations(settings, manager),
                    readyPreparer, StandardTestDispatcher(testScheduler))

                PromotionLaunch.parse(arrayOf("--restore-backup", fixture.record.data.toString()))!!.open(controller)
                val result = controller.state.first { it.backupRestoration != null || it.inputError != null }

                assertSoftly {
                    result.backupRestoration?.original shouldBe fixture.original
                    result.backupRestoration?.backup shouldBe fixture.record.data
                    result.demotion shouldBe null
                    result.inputError shouldBe null
                    result.activity shouldBe emptyList()
                    Files.exists(fixture.original) shouldBe false
                    Files.readString(fixture.record.data) shouldBe "snapshot"
                }
            }
        }
    }

    @Nested
    inner class Demotion {
        @Test
        @Tag("symlink-success")
        fun `on any managed selection the whole game waits for demotion confirmation`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            runTest {
                val fixture = promotedFixture(root)
                val original = fixture.original
                val destination = fixture.destination
                val backup = root.resolve("backups/game")
                val selections = listOf(original, original.resolve("nested/save"), destination, destination.resolve("nested"),
                    destination.resolve("nested/save"), backup, backup.resolve("data"), backup.resolve("data/nested/save"),
                    backup.resolve("backup.json"))
                selections.forEach { selection ->
                    val controller = AppController(backgroundScope, fixture.settings, fixture.operations, readyPreparer,
                        StandardTestDispatcher(testScheduler))

                    PromotionLaunch.parse(arrayOf("--demote", selection.toString()))!!.open(controller)
                    val result = controller.state.first { it.demotion != null || it.backupRestoration != null || it.inputError != null }

                    assertSoftly {
                        result.demotion?.original shouldBe original
                        result.demotion?.destination shouldBe destination
                        result.backupRestoration shouldBe null
                        result.inputError shouldBe null
                        result.activity shouldBe emptyList()
                        Files.isSymbolicLink(original) shouldBe true
                        Files.readString(destination.resolve("nested/save")) shouldBe "latest"
                        fixture.settings.remembered.value shouldBe emptySet()
                    }
                }
            }
        }

        @Test
        @Tag("symlink-success")
        fun `on a legacy destination absent from settings its index still opens demotion`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            runTest {
                val original = root.resolve("game")
                val directory = Files.createDirectory(root.resolve("games"))
                val destination = Files.writeString(directory.resolve("game"), "latest")
                Files.createSymbolicLink(original, destination)
                PromotionIndex().write(directory, listOf(IndexedPromotion("game", original, TransferState.PROMOTED)))
                val settings = AppTestSettings(default = null)
                val manager = selectionManager(root, settings)
                val controller = AppController(backgroundScope, settings, ManagerPromotionOperations(settings, manager),
                    readyPreparer, StandardTestDispatcher(testScheduler))

                PromotionLaunch.parse(arrayOf("--demote", destination.toString()))!!.open(controller)
                val result = controller.state.first { it.demotion != null || it.inputError != null }

                assertSoftly {
                    result.demotion?.original shouldBe original
                    result.demotion?.backup shouldBe null
                    result.activity shouldBe emptyList()
                    settings.remembered.value shouldBe emptySet()
                }
            }
        }

        @Test
        @Tag("symlink-success")
        fun `on confirming an explorer demotion latest contents return and both extra copies disappear`() = withTestDirectory { root ->
            requireSymlinkSupport(root)
            runTest {
                val fixture = promotedFixture(root)
                val controller = AppController(backgroundScope, fixture.settings, fixture.operations, readyPreparer,
                    StandardTestDispatcher(testScheduler))
                PromotionLaunch.parse(arrayOf("--demote", fixture.destination.resolve("nested/save").toString()))!!.open(controller)
                controller.state.first { it.demotion != null || it.inputError != null }

                controller.confirmDemote()
                val result = controller.state.first { it.activity.singleOrNull()?.status in setOf(ActivityStatus.SUCCEEDED, ActivityStatus.FAILED) && !it.refreshing }

                assertSoftly {
                    result.activity.singleOrNull()?.status shouldBe ActivityStatus.SUCCEEDED
                    Files.isSymbolicLink(fixture.original) shouldBe false
                    Files.readString(fixture.original.resolve("nested/save")) shouldBe "latest"
                    Files.exists(fixture.destination) shouldBe false
                    Files.exists(root.resolve("backups/game")) shouldBe false
                }
            }
        }
    }

}

private data class PromotedFixture(val original: Path, val destination: Path, val settings: AppTestSettings,
    val operations: ManagerPromotionOperations)

private suspend fun promotedFixture(root: Path): PromotedFixture {
    val original = Files.createDirectory(root.resolve("game"))
    Files.writeString(Files.createDirectory(original.resolve("nested")).resolve("save"), "snapshot")
    val directory = Files.createDirectory(root.resolve("games"))
    val promotingSettings = AppTestSettings(default = null)
    selectionManager(root, promotingSettings).promote(original, directory)
    Files.writeString(directory.resolve("game/nested/save"), "latest")
    val settings = AppTestSettings(default = null)
    return PromotedFixture(original, directory.resolve("game"), settings,
        ManagerPromotionOperations(settings, selectionManager(root, settings)))
}

private fun selectionManager(root: Path, settings: AppTestSettings) = PromotionManager(settings, GameFileOperations(),
    PromotionIndex(), BackupStore({ root.resolve("backups") }, { listOf(root) }))
