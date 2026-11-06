package com.bkahlert.gametier.ui

import com.bkahlert.gametier.files.TransferProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ActivityProgressUpdates(
    private val scope: CoroutineScope,
    private val publish: (TransferProgress) -> Unit,
) {
    private val lock = Any()
    private var latest: TransferProgress? = null
    private var revision = 0L
    private var publishedRevision = 0L
    private var pending: Job? = null
    private var closed = false

    fun report(progress: TransferProgress): Unit = synchronized(lock) {
        if (closed || progress == latest) return@synchronized
        val previous = latest
        latest = progress
        val currentRevision = ++revision
        val boundary = previous == null || previous.phase != progress.phase || previous.recovering != progress.recovering ||
            previous.totalBytes != progress.totalBytes ||
            (progress.totalBytes > 0 && progress.transferredBytes >= progress.totalBytes)
        if (boundary) {
            pending?.cancel()
            pending = null
            scope.launch { synchronized(lock) { publish(progress, currentRevision) } }
        } else if (pending == null) {
            pending = scope.launch(start = CoroutineStart.LAZY) {
                delay(100)
                synchronized(lock) {
                    pending = null
                    latest?.let { publish(it, revision) }
                }
            }.also { it.start() }
        }
    }

    fun close(): TransferProgress? = synchronized(lock) {
        closed = true
        pending?.cancel()
        pending = null
        latest
    }

    private fun publish(progress: TransferProgress, currentRevision: Long) {
        if (!closed && currentRevision > publishedRevision) {
            publishedRevision = currentRevision
            publish(progress)
        }
    }
}
