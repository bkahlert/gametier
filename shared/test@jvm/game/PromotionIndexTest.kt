package com.bkahlert.gametier.game

import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.file.Files
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.READ
import com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE
import kotlin.test.Test
import org.junit.jupiter.api.Nested

class PromotionIndexTest {
    @Nested
    inner class Persistence {
        @Test
        fun `on long origin text metadata can be read without exhausting the stack`() = withTestDirectory { root ->
            val records = listOf(IndexedPromotion("game", root.resolve("x".repeat(10_000)), TransferState.PROMOTED))
            PromotionIndex().write(root, records)

            val result = PromotionIndex().read(root)

            result shouldBe records
        }

        @Test
        fun `on reopening relative destinations absolute origins and transfer states survive`() = withTestDirectory { root ->
            val records = TransferState.entries.map { state ->
                IndexedPromotion("game-${state.name}", root.resolve("origin-${state.name}"), state)
            }
            val index = PromotionIndex()

            val saved = index.write(root, records)
            val result = PromotionIndex().read(root)

            assertSoftly {
                saved shouldBe Unit
                result shouldBe records
                Files.isHidden(root.resolve(".gametier.json")) shouldBe true
                Files.list(root).use { it.count() } shouldBe 1L
            }
        }

        @Test
        fun `on failed replacement the last committed index remains`() = withTestDirectory { root ->
            val previous = listOf(IndexedPromotion("game", root.resolve("origin"), TransferState.PROMOTED))
            PromotionIndex().write(root, previous)
            val index = PromotionIndex { _, _ -> throw IOException("replacement failed") }

            val error = shouldThrow<IOException> { index.write(root, emptyList()) }
            val result = PromotionIndex().read(root)

            assertSoftly {
                error.message.isNullOrBlank() shouldBe false
                result shouldBe previous
                Files.list(root).use { it.count() } shouldBe 1L
            }
        }

        @Test
        fun `on delete locked metadata replacement preserves the previous contents`() = withTestDirectory { root ->
            val previous = listOf(IndexedPromotion("game", root.resolve("origin"), TransferState.PROMOTED))
            PromotionIndex().write(root, previous)

            val error = FileChannel.open(root.resolve(".gametier.json"), READ, NOSHARE_DELETE).use {
                shouldThrow<IOException> { PromotionIndex().write(root, emptyList()) }
            }
            val result = PromotionIndex().read(root)

            assertSoftly {
                error.message.isNullOrBlank() shouldBe false
                result shouldBe previous
                Files.list(root).use { it.count() } shouldBe 1L
            }
        }
    }

    @Nested
    inner class Validation {
        @Test
        fun `on malformed unsupported or unsafe metadata reading preserves the file`() = withTestDirectory { root ->
            val origin = root.resolve("origin").toString().replace("\\", "\\\\")
            val entry = """{"destination":"game","origin":"$origin","state":"PROMOTED"}"""
            val documents = listOf(
                "not json", "{}", """{"version":2,"promotions":[]}""",
                """{"version":1,"promotions":[$entry],"promotions":[]}""",
                """{"version":1,"version":1,"promotions":[]}""",
                """{"version":1,"promotions":[${entry.replace("\"destination\":\"game\"", "\"destination\":\"lost\",\"destination\":\"game\"")}]}""",
                """{"version":1,"promotions":[$entry],"promoti\u006fns":[]}""",
                """{"version":1,"promotions":[$entry,$entry]}""",
                """{"version":1,"promotions":[${entry.replace("game", "../escape")}]}""",
                """{"version":1,"promotions":[${entry.replace("game", ".gametier.json")}]}""",
                """{"version":1,"promotions":[${entry.replace("game", ".GAMETIER.JSON")}]}""",
                """{"version":1,"promotions":[${entry.replace("game", "game.")}]}""",
                """{"version":1,"promotions":[${entry.replace("game", "game ")}]}""",
                """{"version":1,"promotions":[$entry,${entry.replace("\"destination\":\"game\"", "\"destination\":\"other\"")}]}""",
                """{"version":1,"promotions":[${entry.replace(origin, "relative")}]}""",
                """{"version":1,"promotions":[${entry.replace("PROMOTED", "UNKNOWN")}]}""",
                """{"version":1,"promotions":[${entry.replace("\"game\"", "42")}]}""",
            )
            documents.forEach { document ->
                val file = Files.writeString(root.resolve(".gametier.json"), document)

                val error = shouldThrow<IOException> { PromotionIndex().read(root) }
                val result = Files.readString(file)

                assertSoftly {
                    error.message.isNullOrBlank() shouldBe false
                    result shouldBe document
                }
            }
        }
    }
}
