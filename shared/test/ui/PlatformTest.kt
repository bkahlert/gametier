package com.bkahlert.gametier.ui

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlin.test.Test

class PlatformTest {
    @Test
    fun `platform name is reported`() {
        val result = getPlatform().name
        result.shouldNotBeBlank()
    }

    @Test
    fun `greeting is built from the platform name`() {
        val result = Greeting().greet()
        result shouldBe "Hello, ${getPlatform().name}!"
    }
}
