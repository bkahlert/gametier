package com.bkahlert.gametier.ui

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

class RestartSelectionTest {
    @Test
    fun `on multiple promotions parsing retains order and destinations`() {
        val result = RestartSelection.parse(arrayOf(
            "--resume-promotion", appPath("one").toString(), appPath("first").toString(),
            "--resume-promotion", appPath("two").toString(), appPath("second").toString(),
            "--ready", "1234", "token",
        ))

        assertSoftly {
            result?.requests shouldBe listOf(
                TransferRequest(TransferKind.PROMOTE, appPath("one"), appPath("first"), appPath("first/one")),
                TransferRequest(TransferKind.PROMOTE, appPath("two"), appPath("second"), appPath("second/two")),
            )
            result?.port shouldBe 1234
            result?.token shouldBe "token"
        }
    }

    @Test
    fun `on a legacy single promotion parsing still restores the selection`() {
        val result = RestartSelection.parse(arrayOf(
            "--resume-promotion", appPath("one").toString(), appPath("first").toString(), "--ready", "1234", "token",
        ))

        result?.requests shouldBe listOf(TransferRequest(TransferKind.PROMOTE, appPath("one"), appPath("first"), appPath("first/one")))
    }

    @Test
    fun `on malformed batches no partial selection is accepted`() {
        val first = listOf("--resume-promotion", appPath("one").toString(), appPath("first").toString())
        val ready = listOf("--ready", "1234", "token")
        val malformed = listOf(
            ready,
            first + listOf("--resume-promotion", appPath("two").toString()) + ready,
            first + listOf("--unknown", appPath("two").toString(), appPath("second").toString()) + ready,
            first + listOf("--resume-promotion", "relative", appPath("second").toString()) + ready,
            first + listOf("--resume-promotion", appPath("two").toString(), "relative") + ready,
            first + listOf("--resume-promotion", appPath("two").root.toString(), appPath("second").toString()) + ready,
            first + listOf("--ready", "65536", "token"),
            first + listOf("--ready", "0", "token"),
            first + listOf("--ready", "not-a-port", "token"),
            first + listOf("--ready", "1234", ""),
        )

        malformed.forEach { arguments ->
            val result = shouldThrow<IllegalArgumentException> { RestartSelection.parse(arguments.toTypedArray()) }

            result.message.orEmpty() shouldContain "restart"
        }
    }

    @Test
    fun `on empty or demotion selections restart is rejected`() {
        val demotion = TransferRequest(TransferKind.DEMOTE, appPath("one"), appPath("first"), appPath("first/one"))

        val empty = shouldThrow<IllegalArgumentException> { RestartSelection(emptyList(), 1234, "token") }
        val demote = shouldThrow<IllegalArgumentException> { RestartSelection(listOf(demotion), 1234, "token") }

        assertSoftly {
            empty.message shouldBe "Only unstarted promotions can be reopened"
            demote.message shouldBe "Only unstarted promotions can be reopened"
        }
    }
}
