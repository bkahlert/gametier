package com.bkahlert.gametier

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreAppSettingsTest {
    @Nested
    inner class Preference {
        @Test
        fun `on missing preference the folder is unset`() = runTest {
            val settings = DataStoreAppSettings(MemoryPreferencesStore())

            val result = settings.defaultPromotionFolder.first()

            result shouldBe null
        }

        @Test
        fun `on setting and replacing an unavailable folder the chosen text is retained`() = runTest {
            val settings = DataStoreAppSettings(MemoryPreferencesStore())
            val original = Path.of("missing", "..", "Games").toAbsolutePath().toString() + "/"
            val replacement = Path.of("another-missing-folder").toAbsolutePath().toString()

            val saved = settings.setDefaultPromotionFolder(original)
            val first = settings.defaultPromotionFolder.first()
            val replaced = settings.setDefaultPromotionFolder(replacement)
            val second = settings.defaultPromotionFolder.first()

            assertSoftly {
                saved shouldBe Unit
                first shouldBe original
                replaced shouldBe Unit
                second shouldBe replacement
            }
        }

        @Test
        fun `on clearing only the promotion preference is removed`() = runTest {
            val other = stringPreferencesKey("another_setting")
            val store = MemoryPreferencesStore(preferencesOf(other to "retained"))
            val settings = DataStoreAppSettings(store)
            settings.setDefaultPromotionFolder(Path.of("Games").toAbsolutePath().toString())

            val cleared = settings.clearDefaultPromotionFolder()
            val result = settings.defaultPromotionFolder.first()
            val remaining = store.data.first()

            assertSoftly {
                cleared shouldBe Unit
                result shouldBe null
                remaining[other] shouldBe "retained"
            }
        }

        @Test
        fun `on clearing an unset preference it remains unset`() = runTest {
            val settings = DataStoreAppSettings(MemoryPreferencesStore())

            val cleared = settings.clearDefaultPromotionFolder()
            val result = settings.defaultPromotionFolder.first()

            assertSoftly {
                cleared shouldBe Unit
                result shouldBe null
            }
        }

        @Test
        fun `on changes observers receive the initial and distinct committed values`() = runTest {
            val store = MemoryPreferencesStore()
            val settings = DataStoreAppSettings(store)
            val folder = Path.of("Games").toAbsolutePath().toString()
            val observations = mutableListOf<String?>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                settings.defaultPromotionFolder.toList(observations)
            }

            val saved = settings.setDefaultPromotionFolder(folder)
            val repeated = settings.setDefaultPromotionFolder(folder)
            val unrelated = store.updateData { preferences ->
                preferences.toMutablePreferences().apply { this[stringPreferencesKey("another_setting")] = "changed" }
            }
            val cleared = settings.clearDefaultPromotionFolder()

            assertSoftly {
                saved shouldBe Unit
                repeated shouldBe Unit
                unrelated[stringPreferencesKey("another_setting")] shouldBe "changed"
                cleared shouldBe Unit
                observations.shouldContainExactly(null, folder, null)
            }
        }
    }

    @Nested
    inner class Validation {
        @Test
        fun `on blank relative or malformed input the previous preference is retained`() = runTest {
            val store = MemoryPreferencesStore()
            val settings = DataStoreAppSettings(store)
            val original = Path.of("Games").toAbsolutePath().toString()
            settings.setDefaultPromotionFolder(original)
            store.writeFailure = IOException("must validate before writing")

            listOf("", "  \t", "relative/Games", "bad\u0000path").forEach { folder ->
                val error = shouldThrow<IllegalArgumentException> { settings.setDefaultPromotionFolder(folder) }
                val result = settings.defaultPromotionFolder.first()

                assertSoftly {
                    error.message.isNullOrBlank() shouldBe false
                    result shouldBe original
                }
            }
        }

        @Test
        fun `on invalid persisted text reading fails instead of returning unset`() = runTest {
            listOf("", "relative/Games", "bad\u0000path").forEach { folder ->
                val settings = DataStoreAppSettings(MemoryPreferencesStore(
                    preferencesOf(stringPreferencesKey("default_promotion_folder") to folder),
                ))

                val result = shouldThrow<IllegalStateException> { settings.defaultPromotionFolder.first() }

                result.cause.shouldBeInstanceOf<IllegalArgumentException>()
            }
        }

        @Test
        fun `on a persisted value of the wrong type reading fails`() = runTest {
            val settings = DataStoreAppSettings(MemoryPreferencesStore(
                preferencesOf(intPreferencesKey("default_promotion_folder") to 42),
            ))

            val result = shouldThrow<IllegalStateException> { settings.defaultPromotionFolder.first() }

            result.cause.shouldBeInstanceOf<ClassCastException>()
        }
    }

    @Nested
    inner class WindowsPaths {
        @Test
        fun `on drive UNC and extended paths the original spelling is retained`() = runTest {
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val settings = DataStoreAppSettings(MemoryPreferencesStore())
            listOf("Z:\\", "Z:/Offline/../Games/", "\\\\server\\share\\Games", "\\\\?\\Z:\\Games")
                .forEach { folder ->
                    val saved = settings.setDefaultPromotionFolder(folder)
                    val result = settings.defaultPromotionFolder.first()

                    assertSoftly {
                        saved shouldBe Unit
                        result shouldBe folder
                    }
                }
        }

        @Test
        fun `on drive-relative root-relative or incomplete UNC paths input is rejected`() = runTest {
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val settings = DataStoreAppSettings(MemoryPreferencesStore())
            listOf("Z:Games", "\\Games", "/Games", "\\\\server", "Z:\\bad<name").forEach { folder ->
                val error = shouldThrow<IllegalArgumentException> { settings.setDefaultPromotionFolder(folder) }
                val result = settings.defaultPromotionFolder.first()

                assertSoftly {
                    error.message.isNullOrBlank() shouldBe false
                    result shouldBe null
                }
            }
        }
    }

    @Nested
    inner class Failures {
        @Test
        fun `on a read failure the original error reaches the collector`() = runTest {
            val failure = IOException("read failed")
            val store = MemoryPreferencesStore().apply { readFailure = failure }
            val settings = DataStoreAppSettings(store)

            val result = shouldThrow<IOException> { settings.defaultPromotionFolder.first() }

            result shouldBeSameInstanceAs failure
        }

        @Test
        fun `on failed updates and clears the error propagates and the previous folder remains`() = runTest {
            val store = MemoryPreferencesStore()
            val settings = DataStoreAppSettings(store)
            val original = Path.of("Games").toAbsolutePath().toString()
            val replacement = Path.of("OtherGames").toAbsolutePath().toString()
            settings.setDefaultPromotionFolder(original)
            val failure = IOException("write failed")
            store.writeFailure = failure

            val updateError = shouldThrow<IOException> { settings.setDefaultPromotionFolder(replacement) }
            val clearError = shouldThrow<IOException> { settings.clearDefaultPromotionFolder() }
            val result = settings.defaultPromotionFolder.first()

            assertSoftly {
                updateError shouldBeSameInstanceAs failure
                clearError shouldBeSameInstanceAs failure
                result shouldBe original
            }
        }
    }
}
