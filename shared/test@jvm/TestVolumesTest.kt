package com.bkahlert.gametier

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

class TestVolumesTest {
    @Test
    fun `on a successful action configured volumes are exposed and the environment is restored`() =
        withTestDirectory { root ->
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val result = runVolumeFacility(root, """
                ${'$'}env:GAMETIER_TEST_VOLUMES = 'previous'
                Invoke-WithTestVolumes -ExistingVolumes @('one', 'two', 'three', 'four') -Action {
                    ${'$'}env:GAMETIER_TEST_VOLUMES | Set-Content -LiteralPath (Join-Path ${'$'}OutputDirectory 'during')
                }
                ${'$'}env:GAMETIER_TEST_VOLUMES | Set-Content -LiteralPath (Join-Path ${'$'}OutputDirectory 'after')
            """.trimIndent())

            assertSoftly {
                result.first shouldBe 0
                Files.readString(root.resolve("during")).trim() shouldBe "one;two;three;four"
                Files.readString(root.resolve("after")).trim() shouldBe "previous"
            }
        }

    @Test
    fun `on action failure the original error survives and the environment is restored`() = withTestDirectory { root ->
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val result = runVolumeFacility(root, """
            ${'$'}env:GAMETIER_TEST_VOLUMES = 'previous'
            try {
                Invoke-WithTestVolumes -ExistingVolumes @('one', 'two', 'three', 'four') -Action { throw 'action failed' }
            } catch {
                ${'$'}_.Exception.Message | Set-Content -LiteralPath (Join-Path ${'$'}OutputDirectory 'error')
            }
            ${'$'}env:GAMETIER_TEST_VOLUMES | Set-Content -LiteralPath (Join-Path ${'$'}OutputDirectory 'after')
        """.trimIndent())

        assertSoftly {
            result.first shouldBe 0
            Files.readString(root.resolve("error")) shouldContain "action failed"
            Files.readString(root.resolve("after")).trim() shouldBe "previous"
        }
    }

    @Test
    fun `on invalid volume count the action is not invoked`() = withTestDirectory { root ->
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val result = runVolumeFacility(root, """
            ${'$'}env:GAMETIER_TEST_VOLUMES = 'previous'
            try {
                Invoke-WithTestVolumes -ExistingVolumes @('one') -Action {
                    'invoked' | Set-Content -LiteralPath (Join-Path ${'$'}OutputDirectory 'during')
                }
            } catch {
                ${'$'}_.Exception.Message | Set-Content -LiteralPath (Join-Path ${'$'}OutputDirectory 'error')
            }
            ${'$'}env:GAMETIER_TEST_VOLUMES | Set-Content -LiteralPath (Join-Path ${'$'}OutputDirectory 'after')
        """.trimIndent())

        assertSoftly {
            result.first shouldBe 0
            Files.exists(root.resolve("during")) shouldBe false
            Files.readString(root.resolve("error")) shouldContain "four roots"
            Files.readString(root.resolve("after")).trim() shouldBe "previous"
        }
    }

    @Test
    fun `on an initially unset environment it remains unset after the action`() = withTestDirectory { root ->
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val result = runVolumeFacility(root, """
            Remove-Item Env:GAMETIER_TEST_VOLUMES -ErrorAction SilentlyContinue
            Invoke-WithTestVolumes -ExistingVolumes @('one', 'two', 'three', 'four') -Action { }
            (Test-Path Env:GAMETIER_TEST_VOLUMES) | Set-Content -LiteralPath (Join-Path ${'$'}OutputDirectory 'after')
        """.trimIndent())

        result.first shouldBe 0
        Files.readString(root.resolve("after")).trim() shouldBe "False"
    }
}

private fun runVolumeFacility(root: Path, body: String): Pair<Int, String> {
    val script = Files.writeString(root.resolve("facility.ps1"), """
        param([string] ${'$'}Module, [string] ${'$'}OutputDirectory)
        ${'$'}ErrorActionPreference = 'Stop'
        Import-Module ${'$'}Module -Force
        $body
    """.trimIndent())
    return runTestPowerShell(
        "-File", script.toString(), "-Module", projectRoot().resolve("scripts/TestVolumes.psm1").toString(),
        "-OutputDirectory", root.toString(),
    )
}
