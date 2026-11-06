package com.bkahlert.gametier

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

class ElevatedSymlinkTestsTest {
    @Test
    fun `on an unelevated invocation symlink test execution is refused`() = withTestDirectory { root ->
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        assumeTrue(!isWindowsElevated, "Requires an unelevated process")
        val configuration = Files.writeString(root.resolve("configuration.json"), "{}")

        val result = runTestPowerShell(
            "-File", projectRoot().resolve("scripts/Test-ElevatedSymlinks.ps1").toString(),
            "-Configuration", configuration.toString(),
        )

        result.first shouldBe 1
        result.second shouldContain "require an elevated"
    }
}
