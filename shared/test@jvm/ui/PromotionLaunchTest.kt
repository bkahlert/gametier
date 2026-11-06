package com.bkahlert.gametier.ui

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PromotionLaunchTest {
    @Test
    fun `on demotion or recovery startup the selected path is retained`() {
        val path = appPath("Games 雪/Some Game/save.dat")

        val result = listOf("--demote", "--restore-backup").map { argument ->
            PromotionLaunch.parse(arrayOf(argument, path.toString()))
        }

        result.map { it?.original } shouldBe listOf(path, path)
    }

    @Test
    fun `on malformed demotion or recovery arguments no partial request is accepted`() {
        listOf("--demote", "--restore-backup").forEach { argument ->
            listOf(arrayOf(argument), arrayOf(argument, "relative"), arrayOf(argument, ""),
                arrayOf(argument, appPath("game").root.toString()),
                arrayOf(argument, appPath("one").toString(), appPath("two").toString()),
                arrayOf(argument, "bad\u0000path")).forEach { arguments ->
                shouldThrow<IllegalArgumentException> { PromotionLaunch.parse(arguments) }
            }
        }
    }

    @Test
    fun `on an explorer selection the absolute path retains spaces and unicode`() {
        val path = appPath("Games 雪/Some Game.rom")

        val result = PromotionLaunch.parse(arrayOf("--promote", path.toString()))

        result?.original shouldBe path
    }

    @Test
    fun `on normal startup no promotion is requested`() {
        val result = PromotionLaunch.parse(emptyArray())

        result shouldBe null
    }

    @Test
    fun `on administrator restart the explorer parser leaves arguments untouched`() {
        val resumed = arrayOf("--resume-promotion", appPath("one").toString(), appPath("target").toString(),
            "--ready", "1234", "token")

        val result = listOf(PromotionLaunch.parse(arrayOf("--restart")), PromotionLaunch.parse(resumed))

        result shouldBe listOf(null, null)
    }

    @Test
    fun `on malformed explorer arguments no partial request is accepted`() {
        val arguments = listOf(arrayOf("--promote"), arrayOf("--promote", "relative"),
            arrayOf("--promote", ""), arrayOf("--promote", appPath("game").root.toString()),
            arrayOf("--promote", appPath("one").toString(), appPath("two").toString()),
            arrayOf("--promote", "bad\u0000path"))

        arguments.forEach { args ->
            val result = shouldThrow<IllegalArgumentException> { PromotionLaunch.parse(args) }

            result.message.orEmpty() shouldContain "promotion"
        }
    }

    @Test
    fun `on opening without a destination selection waits for review without transferring`() = runTest {
        val operations = AppTestOperations()
        val app = AppController(backgroundScope, AppTestSettings(default = null), operations, readyPreparer,
            StandardTestDispatcher(testScheduler))
        val launch = PromotionLaunch.parse(arrayOf("--promote", appPath("game").toString()))!!

        launch.open(app)
        runCurrent()
        val result = app.state.value

        assertSoftly {
            result.screen shouldBe AppScreen.PROMOTIONS
            result.pendingSelection shouldBe listOf(appPath("game"))
            result.defaultDirectory shouldBe null
            operations.started shouldBe emptyList()
            result.activity shouldBe emptyList()
        }
    }

    @Test
    fun `on opening with a destination a review is prepared without transferring`() = runTest {
        val operations = AppTestOperations()
        val app = AppController(backgroundScope, AppTestSettings(), operations, readyPreparer,
            StandardTestDispatcher(testScheduler))

        PromotionLaunch.parse(arrayOf("--promote", appPath("game").toString()))!!.open(app)
        runCurrent()
        val result = app.state.value

        assertSoftly {
            result.review?.items?.single()?.original shouldBe appPath("game")
            result.activity shouldBe emptyList()
            operations.started shouldBe emptyList()
        }
    }
}
