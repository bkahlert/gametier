package com.bkahlert.gametier.game

import com.bkahlert.gametier.testsupport.filesystem.testVolumeCases
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.attribute.DosFileAttributeView
import org.junit.jupiter.api.TestFactory

class BackupStoreIntegrationTest {
    @TestFactory
    fun `on each filesystem created and reused drive storage is hidden`() = testVolumeCases { root ->
        val storage = root.resolve(".gametier")
        val store = BackupStore({ storage.resolve("backups") }, { listOf(root) })
        val first = store.reserve(Files.writeString(root.resolve("first"), "source"), root.resolve("games/first"))
        Files.isHidden(storage) shouldBe true
        Files.getFileAttributeView(storage, DosFileAttributeView::class.java).setHidden(false)

        val second = store.reserve(Files.writeString(root.resolve("second"), "source"), root.resolve("games/second"))

        assertSoftly {
            first.folder shouldBe storage.resolve("backups/first")
            second.folder shouldBe storage.resolve("backups/second")
            Files.isHidden(storage) shouldBe true
        }
    }
}
