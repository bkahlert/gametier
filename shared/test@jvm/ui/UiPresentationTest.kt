package com.bkahlert.gametier.ui

import com.bkahlert.gametier.game.DestinationInventory
import com.bkahlert.gametier.game.DestinationStatus
import com.bkahlert.gametier.game.Promotion
import com.bkahlert.gametier.game.PromotionCondition
import com.bkahlert.gametier.game.TransferState
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import org.junit.jupiter.api.Nested

class UiPresentationTest {
    @Nested
    inner class PromotionRows {
        @Test
        fun `on inconsistent promotion details replace demotion`() {
            val result = promotionPresentation(promotion.copy(condition = PromotionCondition.NEEDS_ATTENTION), emptyList())

            assertSoftly {
                result.status shouldBe "Needs attention"
                result.canDemote shouldBe false
                result.busy shouldBe false
            }
        }

        @Test
        fun `on active or queued work the live state replaces persisted condition`() {
            val queued = activity.copy(status = ActivityStatus.QUEUED)
            val running = activity.copy(status = ActivityStatus.RUNNING)

            val queuedResult = promotionPresentation(promotion, listOf(queued))
            val runningResult = promotionPresentation(promotion, listOf(running))
            val completeResult = promotionPresentation(promotion, listOf(activity.copy(status = ActivityStatus.SUCCEEDED)))

            assertSoftly {
                queuedResult.status shouldBe "Queued"
                queuedResult.canDemote shouldBe false
                runningResult.status shouldBe "Demoting"
                runningResult.busy shouldBe true
                completeResult.status shouldBe "Promoted"
                completeResult.canDemote shouldBe true
            }
        }
    }

    @Nested
    inner class Destinations {
        @Test
        fun `on unreadable storage the list does not claim an empty collection`() {
            val notice = destinationNotice(DestinationInventory(directory, DestinationStatus.UNAVAILABLE), emptySet())
            val empty = promotionsEmptyState(directory, 0, "", hasDestinationProblems = notice != null)

            assertSoftly {
                notice?.title shouldBe "Promotion folder unavailable"
                empty shouldBe PromotionsEmptyState.UNAVAILABLE
            }
        }

        @Test
        fun `on a fresh folder missing metadata is normal but remembered metadata loss needs attention`() {
            val inventory = DestinationInventory(directory, DestinationStatus.MISSING_INDEX)

            val fresh = destinationNotice(inventory, emptySet())
            val remembered = destinationNotice(inventory, setOf(directory.toString()))
            val invalid = destinationNotice(inventory.copy(status = DestinationStatus.INVALID_INDEX), emptySet())
            val unrecognized = destinationNotice(inventory.copy(status = DestinationStatus.AVAILABLE, unrecognized = listOf(directory.resolve("loose"))), emptySet())

            assertSoftly {
                fresh shouldBe null
                remembered?.title shouldBe "Promotion metadata is missing"
                invalid?.title shouldBe "Promotion metadata needs attention"
                unrecognized?.details shouldContain directory.resolve("loose").toString()
            }
        }

        @Test
        fun `on remembered alias metadata loss the canonical folder remains a problem`() {
            val alias = "C:/GameAlias"
            val inventory = DestinationInventory(directory, DestinationStatus.MISSING_INDEX)

            val notice = destinationNotice(inventory, setOf(alias), mapOf(alias to directory))
            val empty = promotionsEmptyState(directory, 0, "", notice != null)

            assertSoftly {
                notice?.title shouldBe "Promotion metadata is missing"
                notice?.details shouldContain directory.toString()
                empty shouldBe PromotionsEmptyState.UNAVAILABLE
            }
        }

        @Test
        fun `on remembered alias inspection settings uses its canonical inventory`() {
            val alias = "C:/GameAlias"
            val identities = mapOf(alias to directory)
            val offline = DestinationInventory(directory, DestinationStatus.UNAVAILABLE)
            val available = offline.copy(status = DestinationStatus.AVAILABLE)

            val unavailableResult = rememberedFolderInventory(alias, listOf(offline), identities)
            val availableResult = rememberedFolderInventory(alias, listOf(available), identities)
            val unrelatedResult = rememberedFolderInventory("C:/Other", listOf(available), identities)

            assertSoftly {
                unavailableResult?.status shouldBe DestinationStatus.UNAVAILABLE
                availableResult?.status shouldBe DestinationStatus.AVAILABLE
                unrelatedResult shouldBe null
            }
        }

        @Test
        fun `on first use empty collection and unmatched search guidance follows context`() {
            val firstUse = promotionsEmptyState(null, 0, "", false)
            val empty = promotionsEmptyState(directory, 0, "", false)
            val unmatched = promotionsEmptyState(directory, 5, "missing", false)

            assertSoftly {
                firstUse shouldBe PromotionsEmptyState.FIRST_USE
                empty shouldBe PromotionsEmptyState.EMPTY
                unmatched shouldBe PromotionsEmptyState.NO_MATCHES
            }
        }
    }

    @Nested
    inner class Reviews {
        @Test
        fun `on mixed readiness only ready items enter confirmation`() {
            val review = SelectionReview(directory, listOf(
                ReviewItem(original, destination, ReviewStatus.READY, "Ready"),
                ReviewItem(original.resolveSibling("skipped"), null, ReviewStatus.SKIPPED, "Already promoted"),
                ReviewItem(original.resolveSibling("blocked"), null, ReviewStatus.BLOCKED, "Occupied"),
            ))

            val result = reviewPresentation(review)
            val noDirectory = reviewPresentation(review.copy(directory = null))
            val problem = reviewPresentation(review.copy(problem = "Unavailable"))
            val noReady = reviewPresentation(review.copy(items = review.items.drop(1)))

            assertSoftly {
                result.ready shouldBe 1
                result.skipped shouldBe 1
                result.blocked shouldBe 1
                result.canConfirm shouldBe true
                noDirectory.canConfirm shouldBe false
                problem.canConfirm shouldBe false
                noReady.canConfirm shouldBe false
            }
        }
    }

    @Nested
    inner class Activity {
        @Test
        fun `on acknowledgment the problem count clears while session results remain`() {
            val items = listOf(
                activity.copy(status = ActivityStatus.RUNNING),
                activity.copy(id = 2, status = ActivityStatus.QUEUED),
                activity.copy(id = 3, status = ActivityStatus.FAILED),
                activity.copy(id = 4, status = ActivityStatus.STOPPED),
                activity.copy(id = 5, status = ActivityStatus.FAILED, acknowledged = true),
            )

            val result = activityPresentation(items)
            val acknowledged = activityPresentation(items.map { it.copy(acknowledged = true) })

            assertSoftly {
                result.running shouldBe 1
                result.queued shouldBe 1
                result.unacknowledged shouldBe 2
                result.total shouldBe 5
                acknowledged.unacknowledged shouldBe 0
                acknowledged.total shouldBe 5
            }
        }

        @Test
        fun `on failure details retain locations phase causes and recovery failures`() {
            val cause = IOException("Drive unavailable")
            val error = IOException("Saving final metadata failed", cause).apply {
                addSuppressed(IOException("Recovery link failed"))
            }

            val result = activityDetails(activity.copy(error = error))

            assertSoftly {
                result shouldContain original.toString()
                result shouldContain directory.toString()
                result shouldContain destination.toString()
                result shouldContain "Saving final metadata failed"
                result shouldContain "Drive unavailable"
                result shouldContain "Recovery link failed"
            }
        }

        @Test
        fun `on cyclic error causes details terminate and preserve both errors`() {
            val outer = IOException("Outer")
            val inner = IOException("Inner", outer)
            outer.initCause(inner)

            val result = errorDetails(outer)

            assertSoftly {
                result shouldContain "Outer"
                result shouldContain "Inner"
            }
        }
    }
}

private val directory = Path.of("C:/FastGames")
private val original = Path.of("C:/Games/Hades")
private val destination = directory.resolve("Hades")
private val promotion = Promotion(original, destination, TransferState.PROMOTED, PromotionCondition.PROMOTED)
private val activity = ActivityItem(1, TransferRequest(TransferKind.DEMOTE, original, directory, destination), ActivityStatus.FAILED)
