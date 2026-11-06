package com.bkahlert.gametier.files

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.nio.file.Path

internal fun assertLinkFailure(error: IOException, link: Path, target: Path, phase: String) = assertSoftly {
    error.message.orEmpty() shouldContain phase
    error.message.orEmpty() shouldContain link.toString()
    error.message.orEmpty() shouldContain target.toString()
    error.cause.shouldNotBeNull()
}
