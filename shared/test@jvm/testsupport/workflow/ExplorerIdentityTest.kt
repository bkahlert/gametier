package com.bkahlert.gametier.testsupport.workflow

import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

class ExplorerIdentityTest {
    @Test
    fun `on staging identity resources the publisher version and application icons are generated`() = withTestDirectory { root ->
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val destination = root.resolve("identity")

        val result = runTestPowerShell(
            "-File", identityBuilder, "-PackageVersion", "1.2.3.4", "-Publisher", "CN=Publisher & Partner",
            "-Destination", destination.toString(), "-StageOnly",
        )

        result.first shouldBe 0
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(destination.resolve("content/AppxManifest.xml").toFile())
        val identity = manifest.getElementsByTagName("Identity").item(0).attributes
        assertSoftly {
            identity.getNamedItem("Name").nodeValue shouldBe "GameTier.Explorer"
            identity.getNamedItem("Publisher").nodeValue shouldBe "CN=Publisher & Partner"
            identity.getNamedItem("Version").nodeValue shouldBe "1.2.3.4"
            listOf("StoreLogo" to 50, "Square44x44Logo" to 44, "Square150x150Logo" to 150).forEach { (name, size) ->
                val image = ImageIO.read(destination.resolve("content/Assets/$name.png").toFile())
                image.width shouldBe size
                image.height shouldBe size
            }
            Files.exists(destination.resolve("GameTier.Explorer.msix")) shouldBe false
        }
    }

    @Test
    fun `on an existing destination staging preserves its contents`() = withTestDirectory { root ->
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val sentinel = Files.writeString(root.resolve("keep.txt"), "untouched")

        val result = runTestPowerShell(
            "-File", identityBuilder, "-PackageVersion", "1.2.3.4", "-Publisher", "CN=Publisher",
            "-Destination", root.toString(), "-StageOnly",
        )

        assertSoftly {
            result.first shouldBe 1
            result.second shouldContain "destination already exists"
            Files.readString(sentinel) shouldBe "untouched"
            Files.list(root).use { it.count() } shouldBe 1L
        }
    }

    @Test
    fun `on a version outside the package range resources are not generated`() = withTestDirectory { root ->
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val destination = root.resolve("identity")

        val result = runTestPowerShell(
            "-File", identityBuilder, "-PackageVersion", "1.2.65536.4", "-Publisher", "CN=Publisher",
            "-Destination", destination.toString(), "-StageOnly",
        )

        assertSoftly {
            result.first shouldBe 1
            result.second shouldContain "between 0 and 65535"
            Files.exists(destination) shouldBe false
        }
    }

    @Test
    fun `on missing release signing inputs no unsigned package is emitted`() = withTestDirectory { root ->
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val destination = root.resolve("identity")

        val result = runTestPowerShell(
            "-File", identityBuilder, "-PackageVersion", "1.2.3.4", "-Publisher", "CN=Publisher",
            "-Destination", destination.toString(),
        )

        assertSoftly {
            result.first shouldBe 1
            result.second shouldContain "certificate thumbprint and timestamp URL are required"
            Files.exists(destination) shouldBe false
        }
    }
}

private val identityBuilder: String
    get() = projectRoot().resolve("desktopApp/packaging/installer/Build-ExplorerIdentity.ps1").toString()
