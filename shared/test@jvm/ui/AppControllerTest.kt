package com.bkahlert.gametier.ui

import com.bkahlert.gametier.game.DestinationInventory
import com.bkahlert.gametier.game.DestinationStatus
import com.bkahlert.gametier.game.Promotion
import com.bkahlert.gametier.game.PromotionCondition
import com.bkahlert.gametier.game.TransferState
import com.bkahlert.gametier.testsupport.filesystem.createTestJunction
import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import org.junit.jupiter.api.Nested

@OptIn(ExperimentalCoroutinesApi::class)
class AppControllerTest {
    @Nested
    inner class Queue {
        @Test
        fun `on confirming additions while busy work runs once in order with captured folders`() = runTest {
            val settings = AppTestSettings()
            val operations = AppTestOperations()
            val gate = CompletableDeferred<Unit>()
            operations.transfer = { if (it.original == appPath("one")) gate.await() }
            val app = AppController(backgroundScope, settings, operations, readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("one"), appPath("two")))
            runCurrent()
            app.confirmReview()
            app.confirmReview()
            runCurrent()
            val running = app.state.value
            app.chooseDefault(appPath("other"))
            runCurrent()
            app.select(listOf(appPath("three")))
            runCurrent()
            app.confirmReview()
            runCurrent()
            val beforeRelease = operations.started.toList()
            gate.complete(Unit)
            runCurrent()
            val result = app.state.value

            assertSoftly {
                running.activity.map { it.status } shouldBe listOf(ActivityStatus.RUNNING, ActivityStatus.QUEUED)
                beforeRelease.map { it.original } shouldBe listOf(appPath("one"))
                operations.started.map { it.original } shouldBe listOf(appPath("one"), appPath("two"), appPath("three"))
                operations.started.map { it.directory } shouldBe listOf(appPath("destination"), appPath("destination"), appPath("other"))
                result.activity.map { it.status } shouldBe List(3) { ActivityStatus.SUCCEEDED }
            }
        }

        @Test
        fun `on stale ready review confirmation reserved originals and targets stay unique`() = runTest {
            val operations = AppTestOperations()
            val gate = CompletableDeferred<Unit>()
            operations.transfer = { gate.await() }
            val app = AppController(backgroundScope, AppTestSettings(), operations, readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("one")))
            runCurrent()
            app.confirmReview()
            runCurrent()
            app.select(listOf(appPath("one"), appPath("elsewhere/one")))
            runCurrent()
            app.confirmReview()
            runCurrent()
            val result = app.state.value

            result.activity.size shouldBe 1
            gate.complete(Unit)
            runCurrent()
        }

        @Test
        fun `on isolated failure exact recovery details survive and independent items continue`() = runTest {
            val operations = AppTestOperations()
            val cause = IOException("move phase")
            val failure = IOException("original and destination", cause).apply { addSuppressed(IOException("recovery")) }
            operations.transfer = { if (it.original == appPath("one")) throw failure }
            val app = AppController(backgroundScope, AppTestSettings(), operations, readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("one"), appPath("two")))
            runCurrent()
            app.confirmReview()
            runCurrent()
            val result = app.state.value
            app.acknowledge(result.activity.first().id)
            runCurrent()

            assertSoftly {
                result.activity.map { it.status } shouldBe listOf(ActivityStatus.FAILED, ActivityStatus.SUCCEEDED)
                result.activity.first().error shouldBeSameInstanceAs failure
                result.activity.first().error?.cause shouldBeSameInstanceAs cause
                result.activity.first().error?.suppressed?.size shouldBe 1
                result.activity.first().request.original shouldBe appPath("one")
                result.activity.first().request.destination shouldBe appPath("destination/one")
                app.state.value.activity.first().acknowledged shouldBe true
            }
        }

        @Test
        fun `on unhealthy destination pending affected work stops while another destination proceeds`() = runTest {
            val operations = AppTestOperations()
            val gate = CompletableDeferred<Unit>()
            operations.transfer = { if (it.original == appPath("one")) { gate.await(); throw IOException("unrelated wording") } }
            operations.health = { if (it == appPath("destination")) "Metadata is unavailable" else null }
            val app = AppController(backgroundScope, AppTestSettings(), operations, readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("one"), appPath("two")))
            runCurrent()
            app.confirmReview()
            runCurrent()
            app.select(listOf(appPath("three")))
            runCurrent()
            app.setReviewDirectory(appPath("other"))
            runCurrent()
            app.confirmReview()
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            val result = app.state.value

            assertSoftly {
                result.activity.map { it.status } shouldBe listOf(ActivityStatus.FAILED, ActivityStatus.STOPPED, ActivityStatus.SUCCEEDED)
                operations.started.map { it.original } shouldBe listOf(appPath("one"), appPath("three"))
            }
        }

        @Test
        fun `on health inspection failure queue conservatively stops the destination`() = runTest {
            val operations = AppTestOperations().apply {
                transfer = { throw IOException("item") }
                health = { throw IOException("inspection") }
            }
            val app = AppController(backgroundScope, AppTestSettings(), operations, readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("one"), appPath("two")))
            runCurrent()
            app.confirmReview()
            runCurrent()
            val result = app.state.value

            result.activity.map { it.status } shouldBe listOf(ActivityStatus.FAILED, ActivityStatus.STOPPED)
        }
    }

    @Nested
    inner class Selection {
        @Test
        fun `on removing a colliding item remaining readiness is checked again with adjustments retained`() = runTest {
            val operations = AppTestOperations()
            val first = appPath("first/Game")
            val second = appPath("second/Game")
            val preparer = SelectionPreparer { paths, directory, _, _, _ ->
                SelectionReview(directory, paths.map {
                    ReviewItem(it, directory?.resolve(it.fileName), if (paths.size > 1) ReviewStatus.BLOCKED else ReviewStatus.READY, "")
                }, if (paths.size > 1) listOf("Collapsed selected children") else emptyList())
            }
            val app = AppController(backgroundScope, AppTestSettings(), operations, preparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(first, second))
            runCurrent()
            val blocked = app.state.value.review
            app.removeReviewItem(second)
            runCurrent()
            val reviewed = app.state.value.review
            app.confirmReview()
            runCurrent()
            val result = app.state.value

            assertSoftly {
                blocked?.items?.map { it.status } shouldBe listOf(ReviewStatus.BLOCKED, ReviewStatus.BLOCKED)
                reviewed?.items?.single()?.status shouldBe ReviewStatus.READY
                reviewed?.adjustments shouldBe listOf("Collapsed selected children")
                result.activity.single().status shouldBe ActivityStatus.SUCCEEDED
                operations.started.single().original shouldBe first
            }
        }

        @Test
        fun `on preparation failure pending paths and exact error remain inspectable`() = runTest {
            val error = IOException("source inspection")
            val app = AppController(backgroundScope, AppTestSettings(), AppTestOperations(),
                SelectionPreparer { _, _, _, _, _ -> throw error }, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("game")))
            runCurrent()
            val result = app.state.value

            assertSoftly {
                result.reviewError shouldBeSameInstanceAs error
                result.pendingSelection shouldBe listOf(appPath("game"))
                result.preparing shouldBe false
                result.review shouldBe null
            }
        }

        @Test
        fun `on replacing or canceling preparation stale results never reopen review`() = runTest {
            val gate = CompletableDeferred<Unit>()
            val preparer = SelectionPreparer { paths, directory, _, _, _ ->
                if (paths.first() == appPath("old")) gate.await()
                readyPreparer.prepare(paths, directory, emptyList(), emptySet(), emptyList())
            }
            val app = AppController(backgroundScope, AppTestSettings(), AppTestOperations(), preparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("old")))
            runCurrent()
            app.select(listOf(appPath("new")))
            runCurrent()
            val replacement = app.state.value
            app.cancelReview()
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            val result = app.state.value

            assertSoftly {
                replacement.review?.items?.map { it.original } shouldBe listOf(appPath("new"))
                result.review shouldBe null
                result.pendingSelection shouldBe emptyList()
                result.preparing shouldBe false
            }
        }

        @Test
        fun `on removing and confirming only remaining ready items run`() = runTest {
            val preparer = SelectionPreparer { paths, directory, _, _, _ ->
                SelectionReview(directory, paths.map { path ->
                    ReviewItem(path, directory?.resolve(path.fileName), when(path.fileName.toString()) {
                        "skip" -> ReviewStatus.SKIPPED
                        "block" -> ReviewStatus.BLOCKED
                        else -> ReviewStatus.READY
                    }, "reason")
                })
            }
            val operations = AppTestOperations()
            val app = AppController(backgroundScope, AppTestSettings(), operations, preparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("remove"), appPath("skip"), appPath("block"), appPath("ready")))
            runCurrent()
            app.removeReviewItem(appPath("remove"))
            runCurrent()
            val review = app.state.value.review
            app.confirmReview()
            runCurrent()
            val result = app.state.value

            assertSoftly {
                review?.items?.size shouldBe 3
                operations.started.map { it.original } shouldBe listOf(appPath("ready"))
                result.review shouldBe null
            }
        }

        @Test
        fun `on review override saved default stays independent`() = runTest {
            val settings = AppTestSettings()
            val app = AppController(backgroundScope, settings, AppTestOperations(), readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("game")))
            runCurrent()
            app.setReviewDirectory(appPath("override"))
            runCurrent()
            val result = app.state.value

            assertSoftly {
                result.review?.directory shouldBe appPath("override")
                result.defaultDirectory shouldBe appPath("destination")
                settings.saved shouldBe emptyList()
            }
        }
    }

    @Nested
    inner class Settings {
        @Test
        fun `on remembered observer failure settled review loses permission to confirm until refresh recovery`() = runTest {
            val failure = IOException("history changed")
            val gate = CompletableDeferred<Unit>()
            var collections = 0
            val settings = AppTestSettings().apply {
                promotionFolders = flow {
                    emit(emptySet())
                    if (++collections == 1) { gate.await(); throw failure }
                }
            }
            val operations = AppTestOperations()
            val app = AppController(backgroundScope, settings, operations, readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("game")))
            runCurrent()
            app.setReviewDirectory(appPath("override"))
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            app.confirmReview()
            runCurrent()
            val failed = app.state.value
            app.refresh()
            runCurrent()
            val recovered = app.state.value

            assertSoftly {
                failed.settingsError shouldBeSameInstanceAs failure
                failed.pendingSelection shouldBe listOf(appPath("game"))
                operations.started shouldBe emptyList()
                recovered.settingsError shouldBe null
                recovered.review?.items?.single()?.status shouldBe ReviewStatus.READY
                recovered.review?.directory shouldBe appPath("override")
            }
        }

        @Test
        fun `on successful default save failed remembered reads are collected again`() = runTest {
            val error = IOException("history read")
            var collections = 0
            val settings = AppTestSettings().apply {
                promotionFolders = flow {
                    if (++collections == 1) throw error
                    emit(setOf(appPath("remembered").toString()))
                }
            }
            val app = AppController(backgroundScope, settings, AppTestOperations(), readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            val failed = app.state.value
            app.select(listOf(appPath("game")))
            app.chooseDefault(appPath("new"))
            runCurrent()
            val result = app.state.value

            assertSoftly {
                failed.settingsError shouldBeSameInstanceAs error
                result.settingsError shouldBe null
                result.rememberedFolders shouldBe setOf(appPath("remembered").toString())
                result.review?.items?.single()?.status shouldBe ReviewStatus.READY
            }
        }

        @Test
        fun `on successful default save persistent history failure stays visible`() = runTest {
            val error = IOException("history read")
            val settings = AppTestSettings().apply { promotionFolders = flow { throw error } }
            val app = AppController(backgroundScope, settings, AppTestOperations(), readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.chooseDefault(appPath("new"))
            runCurrent()
            val result = app.state.value

            assertSoftly {
                result.defaultDirectory shouldBe appPath("new")
                result.settingsError shouldBeSameInstanceAs error
            }
        }

        @Test
        fun `on refresh failed default reads are collected again before pending review resumes`() = runTest {
            val error = IOException("default read")
            var collections = 0
            val settings = AppTestSettings().apply {
                defaultPromotionFolder = flow {
                    if (++collections == 1) throw error
                    emit(appPath("recovered").toString())
                }
            }
            val app = AppController(backgroundScope, settings, AppTestOperations(), readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("game")))
            app.refresh()
            runCurrent()
            val result = app.state.value

            assertSoftly {
                result.defaultDirectory shouldBe appPath("recovered")
                result.settingsError shouldBe null
                result.review?.directory shouldBe appPath("recovered")
            }
        }

        @Test
        fun `on remembered aliases physical identity is available without replacing saved display path`() = withTestDirectory { root ->
            runTest {
                val destination = Files.createDirectory(root.resolve("destination"))
                val alias = root.resolve("alias")
                createTestJunction(alias, destination)
                val offline = root.resolve("offline")
                val settings = AppTestSettings().apply { remembered.value = setOf(alias.toString(), offline.toString()) }
                val app = AppController(backgroundScope, settings, AppTestOperations(), readyPreparer, StandardTestDispatcher(testScheduler))
                runCurrent()
                val result = app.state.value

                assertSoftly {
                    result.rememberedFolders shouldBe setOf(alias.toString(), offline.toString())
                    result.rememberedDirectoryIdentities shouldBe mapOf(alias.toString() to destination, offline.toString() to offline)
                }
            }
        }

        @Test
        fun `on selection before settings load chosen override cannot replace saved default`() = runTest {
            val gate = CompletableDeferred<Unit>()
            val settings = AppTestSettings().apply {
                defaultPromotionFolder = flow { gate.await(); emit(appPath("stored").toString()) }
                promotionFolders = flow { gate.await(); emit(setOf(appPath("remembered").toString())) }
            }
            val app = AppController(backgroundScope, settings, AppTestOperations(), readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("game")))
            app.setReviewDirectory(appPath("override"))
            runCurrent()
            val loading = app.state.value
            gate.complete(Unit)
            runCurrent()
            val result = app.state.value

            assertSoftly {
                loading.settingsLoading shouldBe true
                loading.review shouldBe null
                result.defaultDirectory shouldBe appPath("stored")
                result.review?.directory shouldBe appPath("override")
                settings.saved shouldBe emptyList()
                result.rememberedFolders shouldBe setOf(appPath("remembered").toString())
            }
        }

        @Test
        fun `on first review choice save failure pending paths and inline error survive`() = runTest {
            val failure = IOException("save")
            val settings = AppTestSettings(null).apply { saveError = failure }
            val app = AppController(backgroundScope, settings, AppTestOperations(), readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("game")))
            runCurrent()
            app.setReviewDirectory(appPath("new"))
            runCurrent()
            app.confirmReview()
            runCurrent()
            val result = app.state.value

            assertSoftly {
                result.reviewError shouldBeSameInstanceAs failure
                result.pendingSelection shouldBe listOf(appPath("game"))
                result.defaultDirectory shouldBe null
                result.activity shouldBe emptyList()
            }
        }

        @Test
        fun `on editing while save is pending committed value never erases newer input`() = runTest {
            val gate = CompletableDeferred<Unit>()
            val settings = AppTestSettings().apply { saveGate = gate }
            val app = AppController(backgroundScope, settings, AppTestOperations(), readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.chooseDefault(appPath("saved"))
            runCurrent()
            app.setDefaultInput("still editing")
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            val result = app.state.value

            assertSoftly {
                result.defaultDirectory shouldBe appPath("saved")
                result.defaultInput shouldBe "still editing"
            }
        }

        @Test
        fun `on remembered settings read failure storage error is visible`() = runTest {
            val error = IOException("remembered read")
            val settings = AppTestSettings().apply { promotionFolders = flow { throw error } }
            val app = AppController(backgroundScope, settings, AppTestOperations(), readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            val result = app.state.value

            result.settingsError shouldBeSameInstanceAs error
        }

        @Test
        fun `on first destination choice selection survives persistence and becomes ready`() = runTest {
            val settings = AppTestSettings(null)
            val app = AppController(backgroundScope, settings, AppTestOperations(), readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("game")))
            runCurrent()
            app.chooseDefault(appPath("offline"))
            runCurrent()
            val result = app.state.value

            assertSoftly {
                result.defaultDirectory shouldBe appPath("offline")
                result.pendingSelection shouldBe listOf(appPath("game"))
                result.review?.items?.single()?.status shouldBe ReviewStatus.READY
                settings.saved shouldBe listOf(appPath("offline").toString())
                settings.remembered.value shouldBe emptySet()
            }
        }

        @Test
        fun `on invalid input and persistence failure typed input remains editable`() = runTest {
            val settings = AppTestSettings()
            val app = AppController(backgroundScope, settings, AppTestOperations(), readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.setDefaultInput("relative")
            app.saveDefault()
            runCurrent()
            val invalid = app.state.value
            val error = IOException("store")
            settings.saveError = error
            app.setDefaultInput(appPath("offline").toString())
            app.saveDefault()
            runCurrent()
            val result = app.state.value

            assertSoftly {
                invalid.defaultInput shouldBe "relative"
                (invalid.settingsError is IllegalArgumentException) shouldBe true
                result.defaultInput shouldBe appPath("offline").toString()
                result.settingsError shouldBeSameInstanceAs error
                result.defaultDirectory shouldBe appPath("destination")
                result.savingSettings shouldBe false
            }
        }

        @Test
        fun `on settings read failure input and pending selection stay available`() = runTest {
            val error = IOException("read")
            val settings = AppTestSettings().apply { defaultPromotionFolder = flow { throw error } }
            val app = AppController(backgroundScope, settings, AppTestOperations(), readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.setDefaultInput("typed")
            app.select(listOf(appPath("game")))
            runCurrent()
            val result = app.state.value

            assertSoftly {
                result.settingsError shouldBeSameInstanceAs error
                result.defaultInput shouldBe "typed"
                result.pendingSelection shouldBe listOf(appPath("game"))
            }
        }
    }

    @Nested
    inner class Inventory {
        @Test
        fun `on unavailable destination snapshots session paths stay protected until physical storage is inspectable`() = withTestDirectory { root ->
            runTest {
                val destination = Files.createDirectory(root.resolve("destination"))
                val alias = root.resolve("alias")
                createTestJunction(alias, destination)
                for (status in listOf(DestinationStatus.UNAVAILABLE, DestinationStatus.INVALID_INDEX, DestinationStatus.MISSING_INDEX)) {
                    val original = root.resolve("game-${status.name}")
                    val data = alias.resolve(original.fileName)
                    val independent = root.resolve("independent-${status.name}")
                    val other = root.resolve("other-${status.name}")
                    val operations = AppTestOperations()
                    val app = AppController(backgroundScope, AppTestSettings(alias), operations, readyPreparer, StandardTestDispatcher(testScheduler))
                    runCurrent()
                    app.select(listOf(original))
                    runCurrent()
                    operations.snapshot = listOf(DestinationInventory(destination, status))
                    app.confirmReview()
                    runCurrent()
                    app.select(listOf(data, independent))
                    runCurrent()
                    app.setReviewDirectory(other)
                    runCurrent()
                    app.confirmReview()
                    runCurrent()
                    val protected = operations.started.map { it.original }
                    operations.snapshot = listOf(DestinationInventory(destination, DestinationStatus.AVAILABLE))
                    app.refresh()
                    runCurrent()
                    app.select(listOf(data))
                    runCurrent()
                    app.setReviewDirectory(other)
                    runCurrent()
                    app.confirmReview()
                    runCurrent()
                    val recovered = operations.started.map { it.original }

                    assertSoftly {
                        protected shouldBe listOf(original, independent)
                        recovered shouldBe listOf(original, independent, data)
                    }
                }
            }
        }

        @Test
        fun `on failed transfer and refresh session recovery paths remain protected`() = runTest {
            val operations = AppTestOperations().apply { transfer = { throw IOException("move phase") } }
            val app = AppController(backgroundScope, AppTestSettings(), operations, readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("one")))
            runCurrent()
            operations.inventoryError = IOException("refresh")
            app.confirmReview()
            runCurrent()
            app.select(listOf(appPath("destination/one")))
            runCurrent()
            app.setReviewDirectory(appPath("other"))
            runCurrent()
            app.confirmReview()
            runCurrent()
            val result = app.state.value

            assertSoftly {
                operations.started.map { it.original } shouldBe listOf(appPath("one"))
                result.activity.single().status shouldBe ActivityStatus.FAILED
            }
        }

        @Test
        fun `on alias reconnection refresh updates physical remembered identity without settings emission`() = withTestDirectory { root ->
            runTest {
                val destination = Files.createDirectory(root.resolve("destination"))
                val alias = root.resolve("alias")
                val settings = AppTestSettings().apply { remembered.value = setOf(alias.toString()) }
                val operations = AppTestOperations()
                val app = AppController(backgroundScope, settings, operations, readyPreparer, StandardTestDispatcher(testScheduler))
                runCurrent()
                val disconnected = app.state.value
                createTestJunction(alias, destination)
                operations.snapshot = listOf(DestinationInventory(destination, DestinationStatus.MISSING_INDEX))
                app.refresh()
                runCurrent()
                val result = app.state.value

                assertSoftly {
                    disconnected.rememberedDirectoryIdentities[alias.toString()] shouldBe alias
                    result.rememberedDirectoryIdentities[alias.toString()] shouldBe destination
                }
            }
        }

        @Test
        fun `on post promotion refresh failure session data stays protected while independent additions run`() = runTest {
            val operations = AppTestOperations()
            val app = AppController(backgroundScope, AppTestSettings(), operations, readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("one")))
            runCurrent()
            operations.inventoryError = IOException("refresh")
            app.confirmReview()
            runCurrent()
            app.select(listOf(appPath("destination/one"), appPath("independent")))
            runCurrent()
            app.setReviewDirectory(appPath("other"))
            runCurrent()
            app.confirmReview()
            runCurrent()
            val result = app.state.value

            assertSoftly {
                operations.started.map { it.original } shouldBe listOf(appPath("one"), appPath("independent"))
                result.activity.filter { it.status == ActivityStatus.SUCCEEDED }.size shouldBe 2
                result.inventory shouldBe emptyList()
                (result.inventoryError is IOException) shouldBe true
            }
        }

        @Test
        fun `on selection before initial inventory no review or transfer can use an indexed data directory`() = runTest {
            val gate = CompletableDeferred<Unit>()
            val promotion = Promotion(appPath("library/Game"), appPath("archive/Game"), TransferState.PROMOTED, PromotionCondition.PROMOTED)
            val operations = AppTestOperations().apply {
                inventoryGate = gate
                snapshot = listOf(DestinationInventory(appPath("archive"), DestinationStatus.AVAILABLE, listOf(promotion)))
            }
            val app = AppController(backgroundScope, AppTestSettings(), operations, readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(promotion.destination))
            runCurrent()
            val loading = app.state.value
            app.confirmReview()
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            app.confirmReview()
            runCurrent()
            val result = app.state.value

            assertSoftly {
                loading.review shouldBe null
                loading.pendingSelection shouldBe listOf(promotion.destination)
                result.inventoryLoaded shouldBe true
                operations.started shouldBe emptyList()
            }
        }

        @Test
        fun `on initial inventory failure review cannot transfer blind and refresh resumes pending selection`() = runTest {
            val error = IOException("inventory")
            val operations = AppTestOperations().apply { inventoryError = error }
            val app = AppController(backgroundScope, AppTestSettings(), operations, readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("game")))
            runCurrent()
            app.confirmReview()
            runCurrent()
            val failed = app.state.value
            operations.inventoryError = null
            app.refresh()
            runCurrent()
            val result = app.state.value

            assertSoftly {
                failed.review shouldBe null
                failed.inventoryLoaded shouldBe false
                failed.inventoryError shouldBeSameInstanceAs error
                operations.started shouldBe emptyList()
                result.review?.items?.single()?.status shouldBe ReviewStatus.READY
            }
        }

        @Test
        fun `on stale queued promotion newly indexed data is stopped before it can move`() = runTest {
            val operations = AppTestOperations()
            val app = AppController(backgroundScope, AppTestSettings(), operations, readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("archive/Game")))
            runCurrent()
            val gate = CompletableDeferred<Unit>()
            val promotion = Promotion(appPath("library/Game"), appPath("archive/Game"), TransferState.PROMOTED, PromotionCondition.PROMOTED)
            operations.snapshot = listOf(DestinationInventory(appPath("archive"), DestinationStatus.AVAILABLE, listOf(promotion)))
            operations.inventoryGate = gate
            app.refresh()
            runCurrent()
            app.confirmReview()
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            val result = app.state.value

            assertSoftly {
                result.activity.single().status shouldBe ActivityStatus.STOPPED
                operations.started shouldBe emptyList()
            }
        }

        @Test
        fun `on canceling demotion no work starts and needs attention cannot be demoted`() = runTest {
            val promotion = Promotion(appPath("game"), appPath("destination/game"), TransferState.PROMOTED, PromotionCondition.PROMOTED)
            val operations = AppTestOperations()
            val app = AppController(backgroundScope, AppTestSettings(), operations, readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.requestDemote(promotion)
            app.cancelDemote()
            app.confirmDemote()
            app.requestDemote(promotion.copy(condition = PromotionCondition.NEEDS_ATTENTION))
            runCurrent()
            val result = app.state.value

            assertSoftly {
                result.demotion shouldBe null
                result.activity shouldBe emptyList()
            }
        }

        @Test
        fun `on picker error details are retained until explicitly cleared`() = runTest {
            val error = IOException("native picker")
            val app = AppController(backgroundScope, AppTestSettings(), AppTestOperations(), readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.reportError(error)
            app.setActivityExpanded(true)
            runCurrent()
            val reported = app.state.value
            app.clearError()
            runCurrent()
            val cleared = app.state.value

            assertSoftly {
                reported.inputError shouldBeSameInstanceAs error
                reported.activityExpanded shouldBe true
                cleared.inputError shouldBe null
            }
        }

        @Test
        fun `on refresh failure last snapshot and unavailable notice remain visible`() = runTest {
            val offline = DestinationInventory(appPath("offline"), DestinationStatus.UNAVAILABLE, error = IOException("drive"))
            val operations = AppTestOperations().apply { snapshot = listOf(offline) }
            val app = AppController(backgroundScope, AppTestSettings(), operations, readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            val failure = IOException("refresh")
            operations.inventoryError = failure
            app.refresh()
            runCurrent()
            val result = app.state.value

            assertSoftly {
                result.inventory shouldBe listOf(offline)
                result.inventoryError shouldBeSameInstanceAs failure
                result.refreshing shouldBe false
            }
        }

        @Test
        fun `on refresh during transfer inventory waits and keeps snapshot`() = runTest {
            val gate = CompletableDeferred<Unit>()
            val offline = DestinationInventory(appPath("offline"), DestinationStatus.UNAVAILABLE)
            val operations = AppTestOperations().apply { snapshot = listOf(offline); transfer = { gate.await() } }
            val app = AppController(backgroundScope, AppTestSettings(), operations, readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("game")))
            runCurrent()
            app.confirmReview()
            runCurrent()
            app.refresh()
            runCurrent()
            val result = app.state.value
            val calls = operations.inventoryCalls
            gate.complete(Unit)
            runCurrent()

            assertSoftly {
                result.inventory shouldBe listOf(offline)
                calls shouldBe 1
                operations.inventoryCalls shouldBe 2
            }
        }

        @Test
        fun `on navigation and search matches name original and destination without losing state`() = runTest {
            val first = Promotion(appPath("library/alpha"), appPath("fast/alpha"), TransferState.PROMOTED, PromotionCondition.PROMOTED)
            val second = Promotion(appPath("other/beta"), appPath("cold/beta"), TransferState.PROMOTED, PromotionCondition.PROMOTED)
            val operations = AppTestOperations().apply { snapshot = listOf(DestinationInventory(appPath("fast"), DestinationStatus.AVAILABLE, listOf(first, second))) }
            val app = AppController(backgroundScope, AppTestSettings(), operations, readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.showSettings()
            app.setSearch("LIBRARY")
            runCurrent()
            val original = app.state.value
            app.showPromotions()
            app.setSearch("cold")
            runCurrent()
            val destination = app.state.value
            app.setSearch("ALPHA")
            runCurrent()
            val name = app.state.value

            assertSoftly {
                original.screen shouldBe AppScreen.SETTINGS
                original.filteredPromotions shouldBe listOf(first)
                destination.screen shouldBe AppScreen.PROMOTIONS
                destination.filteredPromotions shouldBe listOf(second)
                name.filteredPromotions shouldBe listOf(first)
            }
        }

        @Test
        fun `on confirmed demotion successful record disappears and session result remains`() = runTest {
            val promotion = Promotion(appPath("original/game"), appPath("destination/game"), TransferState.PROMOTED, PromotionCondition.PROMOTED)
            val operations = AppTestOperations().apply { snapshot = listOf(DestinationInventory(appPath("destination"), DestinationStatus.AVAILABLE, listOf(promotion))) }
            val app = AppController(backgroundScope, AppTestSettings(), operations, readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.requestDemote(promotion)
            runCurrent()
            val before = operations.started.toList()
            val confirmation = app.state.value.demotion
            app.confirmDemote()
            app.confirmDemote()
            runCurrent()
            val result = app.state.value

            assertSoftly {
                before shouldBe emptyList()
                confirmation shouldBe promotion
                result.promotions shouldBe emptyList()
                result.activity.single().status shouldBe ActivityStatus.SUCCEEDED
                result.activity.single().request.kind shouldBe TransferKind.DEMOTE
            }
        }
    }

    @Nested
    inner class Lifetime {
        @Test
        fun `on operation cancellation no transfer failure is fabricated`() = runTest {
            val operations = AppTestOperations().apply { transfer = { throw CancellationException("scope ended") } }
            val app = AppController(backgroundScope, AppTestSettings(), operations, readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("game")))
            runCurrent()
            app.confirmReview()
            runCurrent()
            val result = app.state.value

            result.activity.none { it.status == ActivityStatus.FAILED } shouldBe true
        }

        @Test
        fun `on idle close application can exit`() = runTest {
            val app = AppController(backgroundScope, AppTestSettings(), AppTestOperations(), readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.requestClose()
            runCurrent()
            val result = app.state.value

            result.exitRequested shouldBe true
        }

        @Test
        fun `on close during work keep open leaves active work running`() = runTest {
            val gate = CompletableDeferred<Unit>()
            val operations = AppTestOperations().apply { transfer = { gate.await() } }
            val app = AppController(backgroundScope, AppTestSettings(), operations, readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("game")))
            runCurrent()
            app.confirmReview()
            runCurrent()
            app.requestClose()
            runCurrent()
            val prompt = app.state.value.closePrompt
            app.keepOpen()
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            val result = app.state.value

            assertSoftly {
                prompt shouldBe true
                result.exitRequested shouldBe false
                result.activity.single().status shouldBe ActivityStatus.SUCCEEDED
            }
        }

        @Test
        fun `on close when finished queued transfers drain before exit`() = runTest {
            val gate = CompletableDeferred<Unit>()
            val operations = AppTestOperations().apply { transfer = { gate.await() } }
            val app = AppController(backgroundScope, AppTestSettings(), operations, readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("one"), appPath("two")))
            runCurrent()
            app.confirmReview()
            runCurrent()
            app.requestClose()
            app.closeWhenFinished()
            runCurrent()
            val waiting = app.state.value.exitRequested
            gate.complete(Unit)
            runCurrent()
            val result = app.state.value

            assertSoftly {
                waiting shouldBe false
                result.activity.map { it.status } shouldBe List(2) { ActivityStatus.SUCCEEDED }
                result.exitRequested shouldBe true
            }
        }

        @Test
        fun `on draining transfer failure application stays open for inspection`() = runTest {
            val gate = CompletableDeferred<Unit>()
            val operations = AppTestOperations().apply { transfer = { gate.await(); throw IOException("move") } }
            val app = AppController(backgroundScope, AppTestSettings(), operations, readyPreparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("game")))
            runCurrent()
            app.confirmReview()
            runCurrent()
            app.closeWhenFinished()
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            val result = app.state.value

            assertSoftly {
                result.exitRequested shouldBe false
                result.activity.single().status shouldBe ActivityStatus.FAILED
            }
        }

        @Test
        fun `on closing during preparation and settings save work finishes before exit`() = runTest {
            val preparation = CompletableDeferred<Unit>()
            val persistence = CompletableDeferred<Unit>()
            val settings = AppTestSettings().apply { saveGate = persistence }
            val preparer = SelectionPreparer { paths, directory, _, _, _ ->
                preparation.await()
                readyPreparer.prepare(paths, directory, emptyList(), emptySet(), emptyList())
            }
            val app = AppController(backgroundScope, settings, AppTestOperations(), preparer, StandardTestDispatcher(testScheduler))
            runCurrent()
            app.select(listOf(appPath("game")))
            app.chooseDefault(appPath("new"))
            runCurrent()
            app.requestClose()
            app.closeWhenFinished()
            runCurrent()
            val busy = app.state.value
            preparation.complete(Unit)
            runCurrent()
            val preparingDone = app.state.value.exitRequested
            persistence.complete(Unit)
            runCurrent()
            val result = app.state.value

            assertSoftly {
                busy.closeWhenFinished shouldBe true
                busy.exitRequested shouldBe false
                preparingDone shouldBe false
                result.defaultDirectory shouldBe appPath("new")
                result.exitRequested shouldBe true
            }
        }
    }
}
