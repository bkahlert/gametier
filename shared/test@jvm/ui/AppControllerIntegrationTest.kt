package com.bkahlert.gametier.ui

import com.bkahlert.gametier.game.isolatedPromotionManager
import com.bkahlert.gametier.game.GameFileOperations
import com.bkahlert.gametier.game.SymlinkPreflightException
import com.bkahlert.gametier.game.sourceBackupFixture
import com.bkahlert.gametier.testsupport.filesystem.createTestJunction
import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Assumptions.assumeTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppControllerIntegrationTest {
    @Nested
    inner class ManagedSources {
        @Test
        fun `on changing destination and refreshing a duplicate managed alias remains blocked`() = withTestDirectory { root ->
            val fixture = managedAliasFixture(root)
            val marker = Files.writeString(fixture.marker, "malformed")
            val other = Files.createDirectory(root.resolve("other-destination"))
            runTest {
                val app = AppController(backgroundScope, AppTestSettings(fixture.directory), AppTestOperations(),
                    FileSelectionPreparer(), StandardTestDispatcher(testScheduler))
                app.select(listOf(fixture.original, fixture.selection))
                val initial = app.state.first { it.review != null && !it.preparing }

                app.setReviewDirectory(other)
                runCurrent()
                val changed = app.state.first { it.review?.directory == other && !it.preparing }
                app.refresh()
                runCurrent()
                val refreshed = app.state.first { it.review != null && !it.preparing && !it.refreshing }

                assertSoftly {
                    listOf(initial, changed, refreshed).forEach { result ->
                        result.review?.items?.single()?.original shouldBe fixture.original
                        result.review?.items?.single()?.status shouldBe ReviewStatus.BLOCKED
                        result.review?.items?.single()?.message.orEmpty() shouldContain marker.toString()
                    }
                    Files.readString(fixture.original) shouldBe "retained"
                    Files.list(other).use { it.count() } shouldBe 0L
                }
            }
        }

        @Test
        fun `on removing an alias by its displayed original it stays removed during refresh`() = withTestDirectory { root ->
            val fixture = managedAliasFixture(root)
            Files.writeString(fixture.marker, "malformed")
            val remaining = Files.writeString(root.resolve("remaining"), "remaining")
            runTest {
                val app = AppController(backgroundScope, AppTestSettings(fixture.directory), AppTestOperations(),
                    FileSelectionPreparer(), StandardTestDispatcher(testScheduler))
                app.select(listOf(fixture.selection, remaining))
                app.state.first { it.review?.items?.size == 2 && !it.preparing }

                app.removeReviewItem(fixture.original)
                runCurrent()
                val removed = app.state.first { it.review != null && !it.preparing }
                app.refresh()
                runCurrent()
                val refreshed = app.state.first { it.review != null && !it.preparing && !it.refreshing }

                assertSoftly {
                    removed.review?.items?.map { it.original } shouldBe listOf(remaining)
                    refreshed.review?.items?.map { it.original } shouldBe listOf(remaining)
                    refreshed.pendingSelection shouldBe listOf(remaining)
                }
            }
        }

        @Test
        fun `on a lexical ancestor marker appearing after review execution refuses before registration`() = withTestDirectory { root ->
            val fixture = managedAliasFixture(root)
            runTest {
                val settings = AppTestSettings(fixture.directory)
                val operations = ManagerPromotionOperations(settings, isolatedPromotionManager(root, settings,
                    GameFileOperations(createLink = { _, _ -> throw IOException("required privilege is not held") })))
                val app = AppController(backgroundScope, settings, operations, FileSelectionPreparer(), StandardTestDispatcher(testScheduler))
                app.select(listOf(fixture.selection))
                val reviewed = app.state.first { it.review != null && !it.preparing }
                val marker = Files.writeString(fixture.marker, "malformed")

                app.confirmReview()
                val result = app.state.first { it.activity.singleOrNull()?.status == ActivityStatus.FAILED && !it.refreshing }

                assertSoftly {
                    reviewed.review?.items?.single()?.status shouldBe ReviewStatus.READY
                    reviewed.review?.items?.single()?.original shouldBe fixture.original
                    result.activity.single().error?.message.orEmpty() shouldContain marker.toString()
                    Files.list(fixture.directory).use { it.count() } shouldBe 0L
                    Files.readString(fixture.original) shouldBe "retained"
                }
            }
        }

        @Test
        fun `on administrator restart the lexical source is rechecked and retained across refresh`() = withTestDirectory { root ->
            val fixture = managedAliasFixture(root)
            runTest {
                val settings = AppTestSettings(fixture.directory)
                val operations = AppTestOperations().apply { transfer = {
                    throw SymlinkPreflightException(fixture.selection, fixture.directory.resolve("game"), "preflight",
                        IOException("required privilege is not held"))
                } }
                val restarted = mutableListOf<TransferRequest>()
                val app = AppController(backgroundScope, settings, operations, FileSelectionPreparer(), StandardTestDispatcher(testScheduler),
                    restartAsAdministrator = { restarted += it })
                app.state.first { it.inventoryLoaded && !it.settingsLoading && !it.refreshing }
                app.select(listOf(fixture.selection))
                app.state.first { it.review != null && !it.preparing }
                app.confirmReview()
                val failed = app.state.first { it.activity.singleOrNull()?.status == ActivityStatus.FAILED && !it.refreshing }
                runCurrent()

                app.requestLinkPermission(failed.activity.single().id)
                runCurrent()
                app.state.value.linkPermission?.size shouldBe 1
                app.confirmLinkPermission()
                runCurrent()
                val requests = RestartSelection.parse(RestartSelection(restarted, 1234, "token").arguments())!!.requests
                Files.writeString(fixture.marker, "malformed")
                val restored = AppController(backgroundScope, settings, operations, FileSelectionPreparer(), StandardTestDispatcher(testScheduler))
                restored.restorePromotions(requests)
                val initial = restored.state.first { it.review != null && !it.preparing }
                restored.refresh()
                runCurrent()
                val refreshed = restored.state.first { it.review != null && !it.preparing && !it.refreshing }

                assertSoftly {
                    restarted.single().original shouldBe fixture.selection
                    initial.review?.items?.single()?.original shouldBe fixture.original
                    initial.review?.items?.single()?.status shouldBe ReviewStatus.BLOCKED
                    refreshed.reviewError shouldBe null
                    refreshed.review?.items?.single()?.status shouldBe ReviewStatus.BLOCKED
                    refreshed.review?.items?.single()?.destination shouldBe fixture.directory.resolve("game")
                    Files.readString(fixture.original) shouldBe "retained"
                }
            }
        }
    }

    @Nested
    inner class SourceBackupRecovery {
        @Test
        fun `on unreadable settings source backup remains available for confirmed recovery`() = withTestDirectory { root ->
            runTest {
                val fixture = sourceBackupFixture(root)
                val settings = AppTestSettings().apply {
                    defaultPromotionFolder = flow { throw java.io.IOException("Default settings unavailable") }
                    promotionFolders = flow { throw java.io.IOException("Remembered settings unavailable") }
                }
                val operations = ManagerPromotionOperations(settings, isolatedPromotionManager(root, settings))
                val app = AppController(backgroundScope, settings, operations, readyPreparer, StandardTestDispatcher(testScheduler))
                val loaded = app.state.first { it.inventoryLoaded && !it.settingsLoading && !it.refreshing }

                app.requestRestoreBackup(loaded.promotions.single())
                app.confirmRestoreBackup()
                val result = app.state.first { it.activity.singleOrNull()?.status == ActivityStatus.SUCCEEDED }

                assertSoftly {
                    loaded.settingsError?.message.isNullOrBlank() shouldBe false
                    result.activity.single().error shouldBe null
                    Files.readString(fixture.original) shouldBe "snapshot"
                }
            }
        }

        @Test
        fun `on unavailable destination source discovered backup restores without destination metadata`() = withTestDirectory { root ->
            runTest {
                val fixture = sourceBackupFixture(root)
                Files.delete(fixture.directory)
                val settings = AppTestSettings()
                val operations = ManagerPromotionOperations(settings, isolatedPromotionManager(root, settings))
                val app = AppController(backgroundScope, settings, operations, readyPreparer, StandardTestDispatcher(testScheduler))
                val loaded = app.state.first { it.inventoryLoaded && !it.refreshing }
                val promotion = loaded.promotions.single()

                app.requestRestoreBackup(promotion)
                runCurrent()
                val confirmation = app.state.value.backupRestoration
                app.confirmRestoreBackup()
                val result = app.state.first { it.activity.singleOrNull()?.status == ActivityStatus.SUCCEEDED && !it.refreshing && it.promotions.isEmpty() }

                assertSoftly {
                    promotion.canRestoreBackup shouldBe true
                    confirmation shouldBe promotion
                    result.activity.single().request.kind shouldBe TransferKind.RESTORE_BACKUP
                    result.activity.single().progress shouldNotBe null
                    Files.readString(fixture.original) shouldBe "snapshot"
                    Files.exists(fixture.record.folder) shouldBe false
                }
            }
        }

        @Test
        fun `on corrupt destination metadata source recovery still succeeds`() = withTestDirectory { root ->
            runTest {
                val fixture = sourceBackupFixture(root)
                Files.writeString(fixture.directory.resolve(".gametier.json"), "damaged")
                val settings = AppTestSettings()
                val operations = ManagerPromotionOperations(settings, isolatedPromotionManager(root, settings))
                val app = AppController(backgroundScope, settings, operations, readyPreparer, StandardTestDispatcher(testScheduler))
                val loaded = app.state.first { it.inventoryLoaded && !it.refreshing }

                app.requestRestoreBackup(loaded.promotions.single())
                app.confirmRestoreBackup()
                val result = app.state.first { it.activity.singleOrNull()?.status == ActivityStatus.SUCCEEDED }

                assertSoftly {
                    result.activity.single().error shouldBe null
                    Files.readString(fixture.original) shouldBe "snapshot"
                    Files.readString(fixture.directory.resolve(".gametier.json")) shouldBe "damaged"
                }
            }
        }

        @Test
        fun `on original occupied after confirmation opens domain refuses stale recovery`() = withTestDirectory { root ->
            runTest {
                val fixture = sourceBackupFixture(root)
                val settings = AppTestSettings()
                val operations = ManagerPromotionOperations(settings, isolatedPromotionManager(root, settings))
                val app = AppController(backgroundScope, settings, operations, readyPreparer, StandardTestDispatcher(testScheduler))
                val loaded = app.state.first { it.inventoryLoaded && !it.refreshing }
                app.requestRestoreBackup(loaded.promotions.single())
                runCurrent()
                Files.writeString(fixture.original, "new occupant")

                app.confirmRestoreBackup()
                val result = app.state.first { it.activity.singleOrNull()?.status == ActivityStatus.FAILED }

                assertSoftly {
                    result.activity.single().error?.message?.contains("occupied") shouldBe true
                    Files.readString(fixture.original) shouldBe "new occupant"
                    Files.readString(fixture.record.data) shouldBe "snapshot"
                }
            }
        }

        @Test
        fun `on promoted target returns after confirmation opens domain refuses stale recovery`() = withTestDirectory { root ->
            runTest {
                val fixture = sourceBackupFixture(root)
                val settings = AppTestSettings()
                val operations = ManagerPromotionOperations(settings, isolatedPromotionManager(root, settings))
                val app = AppController(backgroundScope, settings, operations, readyPreparer, StandardTestDispatcher(testScheduler))
                val loaded = app.state.first { it.inventoryLoaded && !it.refreshing }
                app.requestRestoreBackup(loaded.promotions.single())
                runCurrent()
                Files.writeString(fixture.target, "latest")

                app.confirmRestoreBackup()
                val result = app.state.first { it.activity.singleOrNull()?.status == ActivityStatus.FAILED }

                assertSoftly {
                    result.activity.single().error?.message?.contains("still exists") shouldBe true
                    Files.exists(fixture.original) shouldBe false
                    Files.readString(fixture.target) shouldBe "latest"
                    Files.readString(fixture.record.data) shouldBe "snapshot"
                }
            }
        }
    }

    @Nested
    inner class RestoredPromotions {
        @Test
        fun `on restart changed files and unavailable destinations are rechecked before confirmation`() = withTestDirectory { root ->
            runTest {
                val destination = Files.createDirectory(root.resolve("fast"))
                val other = root.resolve("disconnected")
                val ready = Files.writeString(root.resolve("ready"), "ready")
                val occupied = Files.writeString(root.resolve("occupied"), "original")
                Files.writeString(destination.resolve("occupied"), "existing")
                val disconnected = Files.writeString(root.resolve("disconnected-game"), "data")
                val requests = listOf(
                    TransferRequest(TransferKind.PROMOTE, ready, destination, destination.resolve("ready")),
                    TransferRequest(TransferKind.PROMOTE, root.resolve("missing"), destination, destination.resolve("missing")),
                    TransferRequest(TransferKind.PROMOTE, occupied, destination, destination.resolve("occupied")),
                    TransferRequest(TransferKind.PROMOTE, disconnected, other, other.resolve("disconnected-game")),
                )
                val parsed = requireNotNull(RestartSelection.parse(RestartSelection(requests, 1234, "token").arguments()))
                val operations = AppTestOperations()
                val app = AppController(backgroundScope, AppTestSettings(), operations, FileSelectionPreparer(), StandardTestDispatcher(testScheduler))

                app.restorePromotions(parsed.requests)
                val reviewed = app.state.first { it.review != null && !it.preparing }
                val beforeConfirmation = operations.started.toList()
                app.confirmReview()
                runCurrent()
                val result = operations.started.toList()

                assertSoftly {
                    reviewed.review?.items?.map { it.status } shouldBe listOf(
                        ReviewStatus.READY, ReviewStatus.BLOCKED, ReviewStatus.BLOCKED, ReviewStatus.BLOCKED)
                    reviewed.review?.let(::reviewPresentation)?.canConfirm shouldBe true
                    beforeConfirmation shouldBe emptyList()
                    result shouldBe listOf(requests.first())
                    Files.readString(occupied) shouldBe "original"
                    Files.readString(destination.resolve("occupied")) shouldBe "existing"
                }
            }
        }

        @Test
        fun `on canonicalized restored paths refresh retains the selected destination`() = withTestDirectory { root ->
            runTest {
                val source = Files.writeString(root.resolve("game"), "data")
                val directory = Files.createDirectory(root.resolve("fast"))
                val original = root.resolve("fast/../game")
                val operations = AppTestOperations()
                val app = AppController(backgroundScope, AppTestSettings(), operations, FileSelectionPreparer(), StandardTestDispatcher(testScheduler))
                app.restorePromotions(listOf(TransferRequest(TransferKind.PROMOTE, original, directory, directory.resolve("game"))))
                app.state.first { it.review != null && !it.preparing }

                app.refresh()
                runCurrent()
                val result = app.state.first { it.review != null && !it.preparing && !it.refreshing }

                assertSoftly {
                    result.reviewError shouldBe null
                    result.review?.items?.single()?.original shouldBe source
                    result.review?.items?.single()?.destination shouldBe directory.resolve("game")
                    result.review?.items?.single()?.status shouldBe ReviewStatus.READY
                }
            }
        }
    }
}

private data class ManagedAliasFixture(val original: Path, val selection: Path, val directory: Path, val marker: Path)

private fun managedAliasFixture(root: Path): ManagedAliasFixture {
    assumeTrue(System.getProperty("os.name").startsWith("Windows"))
    val managed = Files.createDirectory(root.resolve("managed"))
    val ordinary = Files.createDirectory(root.resolve("ordinary"))
    val original = Files.writeString(ordinary.resolve("game"), "retained")
    val alias = managed.resolve("alias")
    createTestJunction(alias, ordinary)
    return ManagedAliasFixture(original, alias.resolve("game"), Files.createDirectory(root.resolve("destination")),
        managed.resolve(".gametier.json"))
}
