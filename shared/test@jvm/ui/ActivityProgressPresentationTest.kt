package com.bkahlert.gametier.ui

import com.bkahlert.gametier.files.TransferPhase
import com.bkahlert.gametier.files.TransferProgress
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ActivityProgressPresentationTest {
    @Test
    fun `on measured transfer row shows bytes percentage and determinate fraction`() {
        val result = activityProgressPresentation(running.copy(progress = TransferProgress(TransferPhase.TRANSFERRING, 512, 2048)))

        assertSoftly {
            result.label shouldBe "Transferring"
            result.detail shouldBe "512 B / 2.0 KiB · 25%"
            result.fraction shouldBe 0.25f
        }
    }

    @Test
    fun `on preparing moving finalizing and zero bytes row shows stages without a percentage`() {
        val preparing = activityProgressPresentation(running.copy(progress = TransferProgress(TransferPhase.PREPARING)))
        val moving = activityProgressPresentation(running.copy(progress = TransferProgress(TransferPhase.MOVING, 1024, 1024)))
        val finalizing = activityProgressPresentation(running.copy(progress = TransferProgress(TransferPhase.FINALIZING, 1024, 1024)))
        val empty = activityProgressPresentation(running.copy(progress = TransferProgress(TransferPhase.TRANSFERRING)))

        assertSoftly {
            listOf(preparing.label, moving.label, finalizing.label, empty.label) shouldBe listOf("Preparing", "Moving", "Finalizing", "Transferring")
            listOf(preparing.detail, moving.detail, finalizing.detail, empty.detail) shouldBe List(4) { null }
            listOf(preparing.fraction, moving.fraction, finalizing.fraction, empty.fraction) shouldBe List(4) { null }
        }
    }

    @Test
    fun `on recovery transfer bytes are visibly separate from the failed forward transfer`() {
        val result = activityProgressPresentation(running.copy(progress = TransferProgress(TransferPhase.TRANSFERRING, 1024, 2048, recovering = true)))

        assertSoftly {
            result.label shouldBe "Recovering · Transferring"
            result.detail shouldBe "1.0 KiB / 2.0 KiB · 50%"
            result.fraction shouldBe 0.5f
        }
    }

    @Test
    fun `on terminal result stale progress cannot label success or failure as an active transfer`() {
        val progress = TransferProgress(TransferPhase.TRANSFERRING, 1024, 2048, recovering = true)
        val results = listOf(ActivityStatus.QUEUED, ActivityStatus.SUCCEEDED, ActivityStatus.FAILED, ActivityStatus.STOPPED).map {
            activityProgressPresentation(running.copy(status = it, progress = progress))
        }

        assertSoftly {
            results.map { it.label } shouldBe listOf("Queued", "Completed", "Failed", "Stopped")
            results.map { it.detail } shouldBe List(4) { null }
            results.map { it.fraction } shouldBe List(4) { null }
        }
    }

    @Test
    fun `on large counts formatting remains bounded without arithmetic overflow`() {
        val result = activityProgressPresentation(running.copy(progress = TransferProgress(TransferPhase.TRANSFERRING, Long.MAX_VALUE, Long.MAX_VALUE)))

        assertSoftly {
            result.detail shouldBe "8.0 EiB / 8.0 EiB · 100%"
            result.fraction shouldBe 1f
        }
    }

    @Test
    fun `on an exact percentage floating point precision does not lose one percent`() {
        val result = activityProgressPresentation(running.copy(progress = TransferProgress(TransferPhase.TRANSFERRING, 29, 100)))

        result.detail shouldBe "29 B / 100 B · 29%"
    }
}

private val running = ActivityItem(1, TransferRequest(TransferKind.PROMOTE, appPath("game"), appPath("fast"), appPath("fast/game")), ActivityStatus.RUNNING)
