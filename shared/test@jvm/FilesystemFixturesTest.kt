package com.bkahlert.gametier

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.DosFileAttributeView
import kotlin.test.Test
import org.junit.jupiter.api.Tag

@Tag("symlink-success")
class FilesystemFixturesTest {
    @Test
    fun `on deleting symlink fixtures external targets and their attributes are retained`() = withTestDirectory { root ->
        requireSymlinkSupport(root)
        val target = Files.writeString(root.resolve("target"), "game data")
        val view = Files.getFileAttributeView(target, DosFileAttributeView::class.java)
        view?.setReadOnly(true)
        val fixture = Files.createDirectory(root.resolve("fixture"))
        Files.createSymbolicLink(fixture.resolve("link"), target)

        val result = deleteTestFixture(fixture)

        assertSoftly {
            result shouldBe Unit
            Files.exists(fixture, NOFOLLOW_LINKS) shouldBe false
            Files.readString(target) shouldBe "game data"
            if (view != null) view.readAttributes().isReadOnly shouldBe true
        }
    }

    @Test
    fun `on deleting dangling symlink fixtures cleanup completes`() = withTestDirectory { root ->
        requireSymlinkSupport(root)
        val fixture = Files.createDirectory(root.resolve("fixture"))
        Files.createSymbolicLink(fixture.resolve("dangling"), root.resolve("missing"))

        val result = deleteTestFixture(fixture)

        result shouldBe Unit
        Files.exists(fixture, NOFOLLOW_LINKS) shouldBe false
    }
}
