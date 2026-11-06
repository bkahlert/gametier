package com.bkahlert.gametier.game

import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test

class BackupStoreTest {
    @Test
    fun `on creating drive storage it is hidden`() = withTestDirectory { root ->
        val source = Files.writeString(root.resolve("game"), "source")
        val storage = root.resolve(".gametier")
        val store = BackupStore({ storage.resolve("backups") }, { listOf(root) })

        val result = store.reserve(source, root.resolve("games/game"))

        assertSoftly {
            result.folder shouldBe storage.resolve("backups/game")
            Files.isHidden(storage) shouldBe true
        }
    }

    @Test
    fun `on reusing visible drive storage it becomes hidden`() = withTestDirectory { root ->
        val source = Files.writeString(root.resolve("game"), "source")
        val storage = Files.createDirectory(root.resolve(".gametier"))
        val store = BackupStore({ storage.resolve("backups") }, { listOf(root) })

        val result = store.reserve(source, root.resolve("games/game"))

        assertSoftly {
            result.folder shouldBe storage.resolve("backups/game")
            Files.isHidden(storage) shouldBe true
        }
    }

    @Test
    fun `on another drive backup storage destination reservation refuses`() = withTestDirectory { root ->
        val source = Files.writeString(root.resolve("game"), "source")
        val otherStorage = Files.createDirectories(root.resolve("other-backups/first/data"))
        val store = BackupStore({ path ->
            if (path.startsWith(root.resolve("other-backups"))) root.resolve("other-backups") else root.resolve("backups")
        }, { listOf(root) })

        val result = shouldThrow<IOException> { store.reserve(source, otherStorage.resolve("game")) }

        assertSoftly {
            result.message.orEmpty() shouldContain "backup storage"
            Files.readString(source) shouldBe "source"
            Files.exists(root.resolve("backups/game")) shouldBe false
        }
    }

    @Test
    fun `on selecting backup storage as source or target reservation leaves snapshots untouched`() = withTestDirectory { root ->
        val storage = Files.createDirectories(root.resolve("backups/first"))
        val payload = Files.writeString(storage.resolve("data"), "retained snapshot")
        val source = Files.writeString(root.resolve("source"), "ordinary source")
        val store = BackupStore({ root.resolve("backups") }, { listOf(root) })

        val sourceFailure = shouldThrow<IOException> { store.reserve(payload, root.resolve("games/data")) }
        val targetFailure = shouldThrow<IOException> { store.reserve(source, storage.resolve("destination")) }

        assertSoftly {
            sourceFailure.message.orEmpty() shouldContain "backup storage"
            targetFailure.message.orEmpty() shouldContain "backup storage"
            Files.readString(payload) shouldBe "retained snapshot"
            Files.readString(source) shouldBe "ordinary source"
            Files.exists(root.resolve("backups/data")) shouldBe false
            Files.exists(root.resolve("backups/source")) shouldBe false
        }
    }

    @Test
    fun `on reserved source intent recovery instructions exist before any data moves`() = withTestDirectory { root ->
        val source = Files.writeString(root.resolve("game"), "untouched")
        val destination = root.resolve("games/game")
        val store = BackupStore({ root.resolve("backups") }, { listOf(root) })

        val result = store.reserve(source, destination)
        val discovered = store.discover()

        assertSoftly {
            result.original shouldBe source
            result.destination shouldBe destination
            result.state shouldBe TransferState.PROMOTING
            discovered shouldBe listOf(result)
            Files.readString(source) shouldBe "untouched"
            Files.exists(result.data) shouldBe false
            Files.readString(result.folder.resolve("RESTORE.txt")) shouldContain source.toString()
        }
    }

    @Test
    fun `on malformed source metadata discovery never treats arbitrary payload as a promotion`() = withTestDirectory { root ->
        val folder = Files.createDirectories(root.resolve("backups/game"))
        Files.writeString(folder.resolve("backup.json"), "{\"version\":1}")
        Files.writeString(folder.resolve("data"), "retained")
        val store = BackupStore({ root.resolve("backups") }, { listOf(root) })

        val result = store.discover()

        assertSoftly {
            result shouldBe emptyList()
            Files.readString(folder.resolve("data")) shouldBe "retained"
        }
    }

    @Test
    fun `on overlapping backup paths reservation refuses before creating a record`() = withTestDirectory { root ->
        val source = Files.createDirectory(root.resolve("game"))
        val store = BackupStore({ source.resolve("backups") }, { listOf(root) })

        val result = shouldThrow<IOException> { store.reserve(source, root.resolve("target/game")) }

        assertSoftly {
            result.message.isNullOrBlank() shouldBe false
            Files.list(source).use { it.count() } shouldBe 0L
        }
    }
}

