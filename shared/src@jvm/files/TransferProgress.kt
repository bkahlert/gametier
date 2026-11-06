package com.bkahlert.gametier.files

enum class TransferPhase { PREPARING, MOVING, TRANSFERRING, FINALIZING }

data class TransferProgress(
    val phase: TransferPhase,
    val transferredBytes: Long = 0,
    val totalBytes: Long = 0,
    val recovering: Boolean = false,
)
