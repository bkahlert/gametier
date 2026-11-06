package com.bkahlert.gametier.settings

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.util.UUID
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

class WindowsExplorerIntegrationTest {
    @Test
    fun `on an absent preference integration defaults enabled without creating it`() = withRegistry { key ->
        val integration = WindowsExplorerIntegration(key)

        val result = integration.isEnabled()

        assertSoftly {
            result shouldBe true
            registry("query", "HKCU\\$key").first shouldBe 1
        }
    }

    @Test
    fun `on toggling integration the preference survives a new instance`() = withRegistry { key ->
        val integration = WindowsExplorerIntegration(key)

        integration.setEnabled(false)
        val disabled = WindowsExplorerIntegration(key).isEnabled()
        integration.setEnabled(true)
        val enabled = WindowsExplorerIntegration(key).isEnabled()

        assertSoftly {
            disabled shouldBe false
            enabled shouldBe true
        }
    }

    @Test
    fun `on malformed registry preferences reading reports the failure`() = withRegistry { key ->
        registry("add", "HKCU\\$key", "/v", "ExplorerIntegrationEnabled", "/t", "REG_SZ", "/d", "bad", "/f").first shouldBe 0

        val result = shouldThrow<IOException> { WindowsExplorerIntegration(key).isEnabled() }

        result.message.orEmpty() shouldContain "Explorer integration"
    }

    @Test
    fun `on an unknown preference value reading reports the failure`() = withRegistry { key ->
        registry("add", "HKCU\\$key", "/v", "ExplorerIntegrationEnabled", "/t", "REG_DWORD", "/d", "2", "/f").first shouldBe 0

        val result = shouldThrow<IOException> { WindowsExplorerIntegration(key).isEnabled() }

        result.message.orEmpty() shouldContain "Explorer integration"
    }
}

private fun withRegistry(action: (String) -> Unit) {
    assumeTrue(System.getProperty("os.name").startsWith("Windows"))
    val key = "Software\\GameTier.Tests\\${UUID.randomUUID()}"
    try { action(key) } finally { registry("delete", "HKCU\\$key", "/f") }
}

private fun registry(vararg arguments: String): Pair<Int, String> {
    val process = ProcessBuilder(listOf("reg.exe") + arguments).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    return process.waitFor() to output
}
