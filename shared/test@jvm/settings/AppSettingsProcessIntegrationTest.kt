package com.bkahlert.gametier.settings

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlin.test.Test

class AppSettingsProcessIntegrationTest {
    @Test
    fun `on another process holding the settings lock a cancelled writer never enters`() = withSettingsFile { file ->
        withSettingsProcess(file) { process ->
            process.request("hold") shouldBe "held"
            val coordinator = SettingsCoordinator(file)
            val contended = coordinator.tryLock { it }
            var entered = false
            coroutineScope {
                val writer = async(Dispatchers.IO, start = UNDISPATCHED) { coordinator.lock { entered = true } }

                writer.cancelAndJoin()
            }
            process.request("release") shouldBe "released"
            val available = coordinator.tryLock { it }

            assertSoftly {
                contended shouldBe false
                entered shouldBe false
                available shouldBe true
            }
        }
    }

    @Test
    fun `on another process saving a primed cache reads the committed settings`() = withSettingsFile { file ->
        withSettingsStore(file) { settings ->
            settings.defaultPromotionFolder.first() shouldBe null
            settings.promotionFolders.first() shouldBe emptySet()
            withSettingsProcess(file) { process ->
                process.request("save") shouldBe "saved"

                val result = settings.defaultPromotionFolder.first() to settings.promotionFolders.first()

                result shouldBe (file.parent.resolve("Child default").toString() to setOf(file.parent.resolve("Child history").toString()))
            }
        }
    }

    @Test
    fun `on another process saving an active observer receives the committed default`() = withSettingsFile { file ->
        withSettingsStore(file) { settings ->
            settings.defaultPromotionFolder.first() shouldBe null
            withSettingsProcess(file) { process ->
                coroutineScope {
                    val observation = async(start = UNDISPATCHED) { settings.defaultPromotionFolder.first { it != null } }

                    process.request("save") shouldBe "saved"
                    val result = observation.await()

                    result shouldBe file.parent.resolve("Child default").toString()
                }
            }
        }
    }

    @Test
    fun `on alternating process writes defaults and both remembered folders survive`() = withSettingsFile { file ->
        withSettingsStore(file) { settings ->
            settings.defaultPromotionFolder.first() shouldBe null
            withSettingsProcess(file) { process ->
                process.request("save") shouldBe "saved"
                settings.rememberPromotionFolder(file.parent.resolve("Parent history").toString())

                val result = process.request("read")
                val saved = settings.defaultPromotionFolder.first() to settings.promotionFolders.first()

                assertSoftly {
                    result shouldBe "${file.parent.resolve("Child default")}|Child history,Parent history"
                    saved shouldBe (file.parent.resolve("Child default").toString() to setOf(
                        file.parent.resolve("Child history").toString(), file.parent.resolve("Parent history").toString()))
                }
            }
        }
    }
}
