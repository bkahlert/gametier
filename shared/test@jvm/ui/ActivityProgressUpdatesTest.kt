package com.bkahlert.gametier.ui

import com.bkahlert.gametier.files.TransferPhase
import com.bkahlert.gametier.files.TransferProgress
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityProgressUpdatesTest {
    @Test
    fun `on background callback publication returns to the controller scope`() = runTest {
        val threads = mutableListOf<Thread>()
        val snapshots = mutableListOf<TransferProgress>()
        val controllerThread = Thread.currentThread()
        val progress = ActivityProgressUpdates(backgroundScope) {
            threads += Thread.currentThread()
            snapshots += it
        }
        val snapshot = TransferProgress(TransferPhase.PREPARING)

        withContext(Dispatchers.Default) { progress.report(snapshot) }
        runCurrent()
        val result = snapshots.toList()

        assertSoftly {
            result shouldBe listOf(snapshot)
            threads shouldBe listOf(controllerThread)
        }
    }

    @Test
    fun `on frequent byte callbacks only the latest pending measurement is published`() = runTest {
        val updates = mutableListOf<TransferProgress>()
        val progress = ActivityProgressUpdates(backgroundScope) { updates += it }
        progress.report(TransferProgress(TransferPhase.TRANSFERRING, 1, 1000))
        runCurrent()
        for (bytes in 2L..999L) progress.report(TransferProgress(TransferPhase.TRANSFERRING, bytes, 1000))
        runCurrent()
        val immediate = updates.toList()
        advanceTimeBy(100)
        runCurrent()
        val result = updates.toList()

        assertSoftly {
            immediate.map { it.transferredBytes } shouldBe listOf(1L)
            result.map { it.transferredBytes } shouldBe listOf(1L, 999L)
        }
    }

    @Test
    fun `on stage recovery or total boundary pending measurements cannot replace immediate updates`() = runTest {
        val updates = mutableListOf<TransferProgress>()
        val progress = ActivityProgressUpdates(backgroundScope) { updates += it }
        val start = TransferProgress(TransferPhase.TRANSFERRING, 1, 10)
        val complete = start.copy(transferredBytes = 10)
        val finalizing = complete.copy(phase = TransferPhase.FINALIZING)
        val recovering = start.copy(recovering = true)
        progress.report(start)
        progress.report(start.copy(transferredBytes = 2))
        progress.report(complete)
        progress.report(finalizing)
        progress.report(recovering)
        runCurrent()
        val immediate = updates.toList()
        advanceTimeBy(100)
        runCurrent()
        val result = updates.toList()

        assertSoftly {
            immediate shouldBe listOf(start, complete, finalizing, recovering)
            result shouldBe immediate
        }
    }

    @Test
    fun `on closing pending and late callbacks cannot publish but final snapshot is retained`() = runTest {
        val updates = mutableListOf<TransferProgress>()
        val progress = ActivityProgressUpdates(backgroundScope) { updates += it }
        val latest = TransferProgress(TransferPhase.TRANSFERRING, 4, 10)
        progress.report(latest.copy(transferredBytes = 1))
        runCurrent()
        progress.report(latest)
        val result = progress.close()
        progress.report(latest.copy(transferredBytes = 8))
        advanceTimeBy(100)
        runCurrent()

        assertSoftly {
            result shouldBe latest
            updates.map { it.transferredBytes } shouldBe listOf(1L)
        }
    }
}
