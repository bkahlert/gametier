package com.bkahlert.gametier.ui

import io.kotest.matchers.string.shouldNotBeBlank
import kotlin.test.Test

class PlatformJvmTest {
    @Test
    fun `platform name is reported`() {
        val result = getPlatform().name
        result.shouldNotBeBlank()
    }
}
