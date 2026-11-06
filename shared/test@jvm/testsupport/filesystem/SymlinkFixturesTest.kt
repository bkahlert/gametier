package com.bkahlert.gametier.testsupport.filesystem

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import org.junit.jupiter.api.Tag
import org.opentest4j.TestAbortedException

class SymlinkFixturesTest {
    @Test
    fun `on required unavailable support a failure is reported instead of an assumption`() = withTestDirectory { root ->
        withTestArchive(root) { archive ->
            val result = shouldThrow<IOException> { requireSymlinkSupport(archive, required = true) }

            assertSoftly {
                result.cause.shouldBeInstanceOf<UnsupportedOperationException>()
                Files.list(archive).use { it.count() } shouldBe 0L
            }
        }
    }

    @Test
    fun `on optional unavailable support the prerequisite is reported`() = withTestDirectory { root ->
        withTestArchive(root) { archive ->
            val result = shouldThrow<TestAbortedException> { requireSymlinkSupport(archive, required = false) }

            result.message.orEmpty().contains("unavailable") shouldBe true
            Files.list(archive).use { it.count() } shouldBe 0L
        }
    }

    @Test
    @Tag("symlink-success")
    fun `on available required support the probe leaves no entries behind`() = withTestDirectory { root ->
        requireSymlinkSupport(root)

        val result = requireSymlinkSupport(root, required = true)

        result shouldBe Unit
        Files.list(root).use { it.count() } shouldBe 0L
    }
}
