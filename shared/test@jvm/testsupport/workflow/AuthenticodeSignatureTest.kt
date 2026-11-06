package com.bkahlert.gametier.testsupport.workflow

import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

class AuthenticodeSignatureTest {
    @Test
    fun `on an untrusted self-signed signature the pinned signer is accepted`() {
        val result = verifySignature("Signed")

        assertSoftly {
            result.first shouldBe 0
            result.second.trim() shouldBe "Signature accepted"
        }
    }

    @Test
    fun `on a modified signed payload verification rejects the digest`() {
        val result = verifySignature("Tampered")

        assertSoftly {
            result.first shouldBe 1
            result.second shouldContain "Invalid Authenticode signature"
            result.second shouldContain "0x80096010"
        }
    }

    @Test
    fun `on a different signing certificate verification rejects the signer`() {
        val result = verifySignature("DifferentCertificate")

        assertSoftly {
            result.first shouldBe 1
            result.second shouldContain "Signing certificate does not match"
        }
    }

    @Test
    fun `on an unsigned payload verification rejects the missing signature`() {
        val result = verifySignature("Unsigned")

        assertSoftly {
            result.first shouldBe 1
            result.second shouldContain "Invalid Authenticode signature"
        }
    }
}

private fun verifySignature(scenario: String): Pair<Int, String> {
    assumeTrue(System.getProperty("os.name").startsWith("Windows"))
    lateinit var result: Pair<Int, String>
    withTestDirectory { root ->
        result = runTestPowerShell(
            "-File", projectRoot().resolve("shared/test@jvm/testsupport/workflow/fixtures/Test-AuthenticodeSignature.ps1").toString(),
            "-Module", projectRoot().resolve("desktopApp/packaging/installer/Authenticode.psm1").toString(),
            "-Directory", root.toString(), "-Scenario", scenario,
        )
    }
    return result
}
