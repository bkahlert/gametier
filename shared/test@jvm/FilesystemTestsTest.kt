package com.bkahlert.gametier

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

class FilesystemTestsTest {
    @Test
    fun `on unmet workflow prerequisites tests are not started`() = withTestDirectory { root ->
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val elevated = isWindowsElevated

        val result = runTestPowerShell(
            "-File", projectRoot().resolve("scripts/Test-Filesystems.ps1").toString(),
            "-ExistingVolumes", root.toString(),
        )

        assertSoftly {
            result.first shouldBe 1
            result.second shouldContain if (elevated) "unelevated shell" else "four roots"
        }
    }
}
