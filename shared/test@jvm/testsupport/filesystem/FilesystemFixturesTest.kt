package com.bkahlert.gametier.testsupport.filesystem

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributeView
import kotlin.test.Test
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assumptions.assumeTrue

class FilesystemFixturesTest {
    @Test
    fun `on an aliased parent fixtures use physical paths and are cleaned up`() = withTestDirectory { root ->
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val parent = Files.createDirectory(root.resolve("parent")).toRealPath()
        val alias = root.resolve("alias")
        createTestJunction(alias, parent)
        var fixture: Path? = null

        val result = withTestDirectory(alias) {
            fixture = it
            Files.writeString(it.resolve("game.rom"), "game data")
        }

        assertSoftly {
            result shouldBe Unit
            fixture!!.parent shouldBe parent
            Files.exists(fixture, NOFOLLOW_LINKS) shouldBe false
            Files.list(parent).use { it.count() } shouldBe 0L
        }
    }

    @Test
    @Tag("symlink-success")
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
    @Tag("symlink-success")
    fun `on deleting dangling symlink fixtures cleanup completes`() = withTestDirectory { root ->
        requireSymlinkSupport(root)
        val fixture = Files.createDirectory(root.resolve("fixture"))
        Files.createSymbolicLink(fixture.resolve("dangling"), root.resolve("missing"))

        val result = deleteTestFixture(fixture)

        result shouldBe Unit
        Files.exists(fixture, NOFOLLOW_LINKS) shouldBe false
    }
}
