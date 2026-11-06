package com.bkahlert.gametier.settings

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
    inner class RememberedFolders {
        @Test
        fun `on missing preferences the history is empty`() = runTest {
            val settings = DataStoreAppSettings(MemoryPreferencesStore())

            val result = settings.promotionFolders.first()

            result shouldBe emptySet()
        }

        @Test
        fun `on replacing and clearing the default only explicitly remembered folders remain`() = runTest {
            val settings = DataStoreAppSettings(MemoryPreferencesStore())
            val original = Path.of("missing", "..", "Games").toAbsolutePath().toString() + "/"
            val replacement = Path.of("OtherGames").toAbsolutePath().toString()
            val remembered = Path.of("OfflineGames").toAbsolutePath().toString() + "/"
            settings.rememberPromotionFolder(remembered)

            val saved = settings.setDefaultPromotionFolder(original)
            val replaced = settings.setDefaultPromotionFolder(replacement)
            val cleared = settings.clearDefaultPromotionFolder()
            val result = settings.promotionFolders.first()
            val default = settings.defaultPromotionFolder.first()

            assertSoftly {
                saved shouldBe Unit
                replaced shouldBe Unit
                cleared shouldBe Unit
                result shouldBe setOf(remembered)
                default shouldBe null
            }
        }

        @Test
        fun `on remembering a folder the default stays unchanged and duplicates are suppressed`() = runTest {
            val settings = DataStoreAppSettings(MemoryPreferencesStore())
            val default = Path.of("Games").toAbsolutePath().toString()
            val remembered = Path.of("OfflineGames").toAbsolutePath().toString() + "/"
            settings.setDefaultPromotionFolder(default)

            val saved = settings.rememberPromotionFolder(remembered)
            val repeated = settings.rememberPromotionFolder(remembered)
            val result = settings.promotionFolders.first()
            val currentDefault = settings.defaultPromotionFolder.first()

            assertSoftly {
                saved shouldBe Unit
                repeated shouldBe Unit
                result shouldBe setOf(remembered)
                currentDefault shouldBe default
            }
        }

        @Test
        fun `on remembering without a default it remains unset`() = runTest {
            val settings = DataStoreAppSettings(MemoryPreferencesStore())
            val folder = Path.of("Games").toAbsolutePath().toString()

            val saved = settings.rememberPromotionFolder(folder)
            val result = settings.promotionFolders.first()
            val default = settings.defaultPromotionFolder.first()

            assertSoftly {
                saved shouldBe Unit
                result shouldBe setOf(folder)
                default shouldBe null
            }
        }

        @Test
        fun `on setting a default its folder remains unregistered`() = runTest {
            val store = MemoryPreferencesStore()
            val settings = DataStoreAppSettings(store)
            val folder = Path.of("Games").toAbsolutePath().toString()
            val observations = mutableListOf<Pair<String?, Set<String>>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                store.data.collect {
                    observations += it[stringPreferencesKey("default_promotion_folder")] to
                        it[stringSetPreferencesKey("promotion_folders")].orEmpty()
                }
            }

            val saved = settings.setDefaultPromotionFolder(folder)

            assertSoftly {
                saved shouldBe Unit
                observations.shouldContainExactly(null to emptySet(), folder to emptySet())
            }
        }

        @Test
        fun `on repeated and unrelated updates observers receive only distinct histories`() = runTest {
            val store = MemoryPreferencesStore()
            val settings = DataStoreAppSettings(store)
            val folder = Path.of("Games").toAbsolutePath().toString()
            val observations = mutableListOf<Set<String>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                settings.promotionFolders.toList(observations)
            }

            val saved = settings.rememberPromotionFolder(folder)
            val repeated = settings.rememberPromotionFolder(folder)
            val default = settings.setDefaultPromotionFolder(folder)
            val cleared = settings.clearDefaultPromotionFolder()
            val unrelated = store.updateData {
                it.toMutablePreferences().apply { this[stringPreferencesKey("another_setting")] = "changed" }
            }

            assertSoftly {
                saved shouldBe Unit
                repeated shouldBe Unit
                default shouldBe Unit
                cleared shouldBe Unit
                unrelated[stringPreferencesKey("another_setting")] shouldBe "changed"
                observations.shouldContainExactly(emptySet(), setOf(folder))
            }
        }
    }

    @Nested
    inner class LegacyFolders {
        @Test
        fun `on a legacy default history starts empty without writing`() = runTest {
            val folder = Path.of("Games").toAbsolutePath().toString()
            val store = MemoryPreferencesStore(preferencesOf(stringPreferencesKey("default_promotion_folder") to folder))
                .apply { writeFailure = IOException("reading must not write") }
            val settings = DataStoreAppSettings(store)

            val result = settings.promotionFolders.first()

            result shouldBe emptySet()
        }

        @Test
        fun `on replacing clearing or remembering a legacy default stays separate from history`() = runTest {
            val legacy = Path.of("Games").toAbsolutePath().toString()
            val replacement = Path.of("OtherGames").toAbsolutePath().toString()
            val changes = listOf<suspend (AppSettings) -> Unit>(
                { it.setDefaultPromotionFolder(replacement) },
                { it.clearDefaultPromotionFolder() },
                { it.rememberPromotionFolder(replacement) },
            )
            changes.zip(listOf(emptySet(), emptySet(), setOf(replacement))).forEach { (change, expected) ->
                val settings = DataStoreAppSettings(MemoryPreferencesStore(
                    preferencesOf(stringPreferencesKey("default_promotion_folder") to legacy),
                ))

                val changed = change(settings)
                val result = settings.promotionFolders.first()

                assertSoftly {
                    changed shouldBe Unit
                    result shouldBe expected
                }
            }
        }
    }

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
        fun `on invalid remembered input the history and default are retained`() = runTest {
            val store = MemoryPreferencesStore()
            val settings = DataStoreAppSettings(store)
            val original = Path.of("Games").toAbsolutePath().toString()
            settings.setDefaultPromotionFolder(original)
            settings.rememberPromotionFolder(original)
            store.writeFailure = IOException("must validate before writing")

            listOf("", "  \t", "relative/Games", "bad\u0000path").forEach { folder ->
                val error = shouldThrow<IllegalArgumentException> { settings.rememberPromotionFolder(folder) }
                val history = settings.promotionFolders.first()
                val default = settings.defaultPromotionFolder.first()

                assertSoftly {
                    error.message.isNullOrBlank() shouldBe false
                    history shouldBe setOf(original)
                    default shouldBe original
                }
            }
        }

        @Test
        fun `on invalid persisted history reading and remembering fail without changing storage`() = runTest {
            val folder = Path.of("Games").toAbsolutePath().toString()
            listOf("", "  \t", "relative/Games", "bad\u0000path").forEach { invalid ->
                val initial = preferencesOf(stringSetPreferencesKey("promotion_folders") to setOf(folder, invalid))
                val store = MemoryPreferencesStore(initial)
                val settings = DataStoreAppSettings(store)

                val read = shouldThrow<IllegalStateException> { settings.promotionFolders.first() }
                val remember = shouldThrow<IllegalStateException> { settings.rememberPromotionFolder(folder) }
                val result = store.data.first()

                assertSoftly {
                    listOf(read, remember).forEach {
                        it.cause.shouldBeInstanceOf<IllegalArgumentException>()
                    }
                    result shouldBe initial
                }
            }
        }

        @Test
        fun `on a persisted history of the wrong type reading and remembering fail`() = runTest {
            val initial = preferencesOf(stringPreferencesKey("promotion_folders") to "not a set")
            val store = MemoryPreferencesStore(initial)
            val settings = DataStoreAppSettings(store)
            val folder = Path.of("Games").toAbsolutePath().toString()

            val read = shouldThrow<IllegalStateException> { settings.promotionFolders.first() }
            val remember = shouldThrow<IllegalStateException> { settings.rememberPromotionFolder(folder) }
            val result = store.data.first()

            assertSoftly {
                listOf(read, remember).forEach { it.cause.shouldBeInstanceOf<ClassCastException>() }
                result shouldBe initial
            }
        }

        @Test
        fun `on invalid legacy defaults history can still be read and remembered`() = runTest {
            val folder = Path.of("Games").toAbsolutePath().toString()
            listOf(
                preferencesOf(stringPreferencesKey("default_promotion_folder") to "relative/Games"),
                preferencesOf(intPreferencesKey("default_promotion_folder") to 42),
            ).forEach { initial ->
                val settings = DataStoreAppSettings(MemoryPreferencesStore(initial))

                val initialHistory = settings.promotionFolders.first()
                val saved = settings.rememberPromotionFolder(folder)
                val result = settings.promotionFolders.first()

                assertSoftly {
                    initialHistory shouldBe emptySet()
                    saved shouldBe Unit
                    result shouldBe setOf(folder)
                }
            }
        }

        @Test
        fun `on invalid history default changes preserve the stored history`() = runTest {
            val folder = Path.of("Games").toAbsolutePath().toString()
            listOf(
                preferencesOf(stringSetPreferencesKey("promotion_folders") to setOf("relative/Games")),
                preferencesOf(stringPreferencesKey("promotion_folders") to "not a set"),
            ).forEach { initial ->
                val store = MemoryPreferencesStore(initial)
                val settings = DataStoreAppSettings(store)

                val saved = settings.setDefaultPromotionFolder(folder)
                val default = settings.defaultPromotionFolder.first()
                val cleared = settings.clearDefaultPromotionFolder()
                val result = store.data.first()

                assertSoftly {
                    saved shouldBe Unit
                    default shouldBe folder
                    cleared shouldBe Unit
                    result shouldBe initial
                }
            }
        }

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
        fun `on a history read failure the original error reaches the collector`() = runTest {
            val failure = IOException("read failed")
            val settings = DataStoreAppSettings(MemoryPreferencesStore().apply { readFailure = failure })

            val result = shouldThrow<IOException> { settings.promotionFolders.first() }

            result shouldBeSameInstanceAs failure
        }

        @Test
        fun `on failed default history and clear writes neither stored value changes`() = runTest {
            val store = MemoryPreferencesStore()
            val settings = DataStoreAppSettings(store)
            val original = Path.of("Games").toAbsolutePath().toString()
            val replacement = Path.of("OtherGames").toAbsolutePath().toString()
            settings.setDefaultPromotionFolder(original)
            settings.rememberPromotionFolder(original)
            val failure = IOException("write failed")
            store.writeFailure = failure

            val set = shouldThrow<IOException> { settings.setDefaultPromotionFolder(replacement) }
            val remember = shouldThrow<IOException> { settings.rememberPromotionFolder(replacement) }
            val clear = shouldThrow<IOException> { settings.clearDefaultPromotionFolder() }
            val history = settings.promotionFolders.first()
            val default = settings.defaultPromotionFolder.first()

            assertSoftly {
                set shouldBeSameInstanceAs failure
                remember shouldBeSameInstanceAs failure
                clear shouldBeSameInstanceAs failure
                history shouldBe setOf(original)
                default shouldBe original
            }
        }

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
