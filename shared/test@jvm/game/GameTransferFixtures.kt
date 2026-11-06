package com.bkahlert.gametier.game

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.nio.file.Path

internal fun assertTransferFailure(error: IOException, original: Path, target: Path?, phase: String) = assertSoftly {
    error.message.orEmpty() shouldContain original.toString()
    if (target != null) error.message.orEmpty() shouldContain target.toString()
    error.message.orEmpty() shouldContain phase
    error.cause.shouldNotBeNull()
}
