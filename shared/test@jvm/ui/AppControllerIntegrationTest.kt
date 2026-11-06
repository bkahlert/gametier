package com.bkahlert.gametier.ui

import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import org.junit.jupiter.api.Nested

@OptIn(ExperimentalCoroutinesApi::class)
class AppControllerIntegrationTest {
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
