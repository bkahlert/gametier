package com.bkahlert.gametier.ui

import com.bkahlert.gametier.game.isolatedPromotionManager
import com.bkahlert.gametier.game.sourceBackupFixture
import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import org.junit.jupiter.api.Nested

@OptIn(ExperimentalCoroutinesApi::class)
class AppControllerIntegrationTest {
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
