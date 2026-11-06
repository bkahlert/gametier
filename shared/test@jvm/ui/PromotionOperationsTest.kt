package com.bkahlert.gametier.ui

import com.bkahlert.gametier.game.DestinationStatus
import com.bkahlert.gametier.game.PromotionManager
import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory
import com.bkahlert.gametier.testsupport.filesystem.createTestJunction
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.DosFileAttributeView
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import org.junit.jupiter.api.Nested

class PromotionOperationsTest {
    @Nested
    inner class DestinationHealth {
        @Test
        fun `on remembered alias missing metadata physical destination remains blocked`() = withTestDirectory { root ->
            runBlocking {
                val destination = Files.createDirectory(root.resolve("destination"))
                val alias = root.resolve("alias")
                createTestJunction(alias, destination)
                val settings = AppTestSettings(destination)
                settings.rememberPromotionFolder(alias.toString())

                val result = ManagerPromotionOperations(settings).destinationProblem(destination)

                result.isNullOrBlank() shouldBe false
            }
        }

        @Test
        fun `on read only metadata destination is blocked without changing the index`() = withTestDirectory { root ->
            runBlocking {
                val settings = AppTestSettings(root)
                PromotionManager(settings).registerDirectory(root)
                val index = root.resolve(".gametier.json")
                val view = Files.getFileAttributeView(index, DosFileAttributeView::class.java)
                val original = Files.readString(index)
                view.setReadOnly(true)
                try {
                    val result = ManagerPromotionOperations(settings).destinationProblem(root)

                    assertSoftly {
                        result.isNullOrBlank() shouldBe false
                        Files.readString(index) shouldBe original
                    }
                } finally {
                    view.setReadOnly(false)
                }
            }
        }

        @Test
        fun `on fresh destination health inspection leaves files and settings unchanged`() = withTestDirectory { root ->
            runBlocking {
                val settings = AppTestSettings(root)
                val operations = ManagerPromotionOperations(settings)

                val result = operations.destinationProblem(root)

                assertSoftly {
                    result shouldBe null
                    Files.exists(root.resolve(".gametier.json")) shouldBe false
                    settings.remembered.value shouldBe emptySet()
                }
            }
        }

        @Test
        fun `on missing remembered metadata damaged metadata or missing storage destination is blocked`() = withTestDirectory { root ->
            runBlocking {
                val settings = AppTestSettings(root)
                val operations = ManagerPromotionOperations(settings)
                settings.rememberPromotionFolder(root.toString())

                val missing = operations.destinationProblem(root)
                Files.writeString(root.resolve(".gametier.json"), "broken")
                val damaged = operations.destinationProblem(root)
                val unavailable = operations.destinationProblem(root.resolve("offline"))

                assertSoftly {
                    missing.isNullOrBlank() shouldBe false
                    damaged.isNullOrBlank() shouldBe false
                    unavailable.isNullOrBlank() shouldBe false
                    Files.readString(root.resolve(".gametier.json")) shouldBe "broken"
                }
            }
        }

        @Test
        fun `on healthy registered destination inspection allows further independent work`() = withTestDirectory { root ->
            runBlocking {
                val settings = AppTestSettings(root)
                val manager = PromotionManager(settings)
                manager.registerDirectory(root)
                val operations = ManagerPromotionOperations(settings, manager)

                val health = operations.destinationProblem(root)
                val inventory = operations.inventory()

                assertSoftly {
                    health shouldBe null
                    inventory.single().status shouldBe DestinationStatus.AVAILABLE
                }
            }
        }
    }

    @Nested
    inner class Transfers {
        @Test
        fun `on manager validation failure promotion remains untouched and error retains paths`() = withTestDirectory { root ->
            runBlocking {
                val settings = AppTestSettings(root)
                val original = Files.writeString(root.resolve("game"), "game content")
                val target = Files.createDirectory(root.resolve("target"))
                Files.writeString(target.resolve("game"), "existing")
                val operations = ManagerPromotionOperations(settings)

                val result = shouldThrow<IOException> { operations.promote(original, target) }

                assertSoftly {
                    result.message?.contains(target.resolve("game").toString()) shouldBe true
                    Files.readString(original) shouldBe "game content"
                    Files.readString(target.resolve("game")) shouldBe "existing"
                }
            }
        }
    }
}
