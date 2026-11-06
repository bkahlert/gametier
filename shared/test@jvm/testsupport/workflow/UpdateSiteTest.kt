package com.bkahlert.gametier.testsupport.workflow

import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested

class UpdateSiteTest {
    @Nested
    inner class Inputs {
        @Test
        fun `on missing inputs verification preserves existing files`() = withTestDirectory { root ->
            assumeWindows()
            val sentinel = Files.writeString(root.resolve("keep.txt"), "untouched")

            val result = verify(root.resolve("absent"), root.resolve("absent.dll"))

            assertSoftly {
                result.first shouldBe 1
                result.second shouldContain "Update site directory is missing"
                Files.readString(sentinel) shouldBe "untouched"
                Files.list(root).use { it.count() } shouldBe 1L
            }
        }

        @Test
        fun `on a missing native build verification identifies the fixture`() = withTestDirectory { root ->
            assumeWindows()
            val fixture = UpdateSiteFixture(root)
            Files.delete(fixture.library)

            val result = verify(fixture.site, fixture.library)

            assertSoftly {
                result.first shouldBe 1
                result.second shouldContain "Explorer build is missing"
            }
        }

        @Test
        fun `on missing or ambiguous site artifacts verification identifies each artifact`() = withTestDirectory { root ->
            assumeWindows()
            val artifacts = mapOf(
                "gametier.msix" to "Windows MSIX package",
                "install.exe" to "Windows EXE installer",
                "gametier.appinstaller" to "App Installer update metadata",
                "gametier.cer" to "Windows signing certificate",
            )
            artifacts.entries.forEachIndexed { index, (name, description) ->
                val missing = UpdateSiteFixture(Files.createDirectory(root.resolve("missing-$index")))
                Files.delete(missing.site.resolve(name))

                val missingResult = verify(missing.site, missing.library)

                assertSoftly {
                    missingResult.first shouldBe 1
                    missingResult.second shouldContain "$description expected one item, got 0"
                }
                val ambiguous = UpdateSiteFixture(Files.createDirectory(root.resolve("ambiguous-$index")))
                Files.copy(ambiguous.site.resolve(name), ambiguous.site.resolve("duplicate.${name.substringAfterLast('.')}"))

                val ambiguousResult = verify(ambiguous.site, ambiguous.library)

                assertSoftly {
                    ambiguousResult.first shouldBe 1
                    ambiguousResult.second shouldContain "$description expected one item, got 2"
                }
            }
        }

        @Test
        fun `on missing download artifacts verification names the missing output`() = withTestDirectory { root ->
            assumeWindows()
            listOf("download.html" to "Download page expected one item, got 0", "metadata.properties" to "update-site metadata is missing")
                .forEachIndexed { index, (name, error) ->
                    val fixture = UpdateSiteFixture(Files.createDirectory(root.resolve("missing-$index")))
                    Files.delete(fixture.site.resolve(name))

                    val result = verify(fixture.site, fixture.library)

                    assertSoftly {
                        result.first shouldBe 1
                        result.second shouldContain error
                    }
                }
        }
    }

    @Nested
    inner class PackageContents {
        @Test
        fun `on duplicate archive paths verification rejects ambiguous content`() = withTestDirectory { root ->
            assumeWindows()
            val fixture = UpdateSiteFixture(root)
            fixture.entries["appxmanifest.xml"] = fixture.entries.getValue("AppxManifest.xml")
            fixture.writePackage()

            val result = verify(fixture.site, fixture.library)

            assertSoftly {
                result.first shouldBe 1
                result.second shouldContain "Ambiguous MSIX entries"
            }
        }

        @Test
        fun `on incorrect package contracts verification reports the violated behavior`() = withTestDirectory { root ->
            assumeWindows()
            val mutations = listOf(
                ManifestMutation("identity", "Name=\"GameTier\"", "Name=\"Other\"", "Package identity expected"),
                ManifestMutation("version", "Version=\"1.2.3.0\"", "Version=\"1.2.4.0\"", "Package version expected"),
                ManifestMutation("architecture", "ProcessorArchitecture=\"x64\"", "ProcessorArchitecture=\"arm64\"", "Package architecture expected"),
                ManifestMutation("namespace", "manifest/com/windows10", "manifest/com/invalid", "Packaged Explorer COM class expected"),
                ManifestMutation("com-path", "Path=\"GameTierExplorer.dll\"", "Path=\"app/GameTierExplorer.dll\"", "Explorer COM path expected"),
                ManifestMutation("com-threading", "ThreadingModel=\"STA\"", "ThreadingModel=\"MTA\"", "Explorer COM threading model expected"),
                ManifestMutation("file-verb", "Type=\"*\"", "Type=\".txt\"", "Explorer item type * expected"),
                ManifestMutation("directory-verb", "Type=\"Directory\"", "Type=\"Folder\"", "Explorer item type Directory expected"),
                ManifestMutation("elevation", "Name=\"allowElevation\"", "Name=\"unused\"", "Restricted capability allowElevation expected"),
                ManifestMutation("settings", "LocalAppData)\\GameTierData", "LocalAppData)\\OtherData", "GameTierData must be excluded"),
                ManifestMutation("registry", "<d6:RegistryWriteVirtualization>disabled", "<d6:RegistryWriteVirtualization>enabled", "Registry virtualization policy expected"),
                ManifestMutation("launcher", "Executable=\"GameTier.exe\"", "Executable=\"app/GameTier.exe\"", "Launcher path expected"),
            )
            mutations.forEach { mutation ->
                val fixture = UpdateSiteFixture(Files.createDirectory(root.resolve(mutation.name)))
                fixture.manifest = fixture.manifest.replace(mutation.from, mutation.to)
                fixture.writePackage()

                val result = verify(fixture.site, fixture.library)

                assertSoftly {
                    result.first shouldBe 1
                    result.second shouldContain mutation.error
                }
            }
        }

        @Test
        fun `on absent payloads verification reports each missing asset`() = withTestDirectory { root ->
            assumeWindows()
            val entries = mapOf(
                "GameTier.exe" to "MSIX entry GameTier.exe expected",
                "GameTierExplorer.dll" to "MSIX entry GameTierExplorer.dll expected",
                "GameTierExplorer.LICENSE.txt" to "MSIX entry GameTierExplorer.LICENSE.txt expected",
                "LICENSE" to "MSIX entry LICENSE expected",
                "Assets/logo.png" to "Referenced package logo is missing",
                "AppxSignature.p7x" to "MSIX entry AppxSignature.p7x expected",
                "AppxBlockMap.xml" to "MSIX entry AppxBlockMap.xml expected",
                "app/GameTier.jar" to "Application runtime JARs are missing",
            )
            entries.entries.forEachIndexed { index, (name, error) ->
                val fixture = UpdateSiteFixture(Files.createDirectory(root.resolve("asset-$index")))
                fixture.entries.remove(name)
                fixture.writePackage()

                val result = verify(fixture.site, fixture.library)

                assertSoftly {
                    result.first shouldBe 1
                    result.second shouldContain error
                }
            }
        }

        @Test
        fun `on a stale burned in launcher path verification rejects the launcher`() = withTestDirectory { root ->
            assumeWindows()
            val fixture = UpdateSiteFixture(root)
            fixture.entries["GameTier.exe"] = "MZ\u0000-Dgametier.appPath=GameTier.exe\u0000".toByteArray()
            fixture.writePackage()

            val result = verify(fixture.site, fixture.library)

            assertSoftly {
                result.first shouldBe 1
                result.second shouldContain "Launcher path relative to app/ runtime directory expected"
            }
        }

        @Test
        fun `on complete unsigned artifacts verification requires an authentic signature`() = withTestDirectory { root ->
            assumeWindows()
            val fixture = UpdateSiteFixture(root)

            val result = verify(fixture.site, fixture.library)

            assertSoftly {
                result.first shouldBe 1
                result.second shouldContain "Invalid Authenticode signature"
            }
        }
    }

    @Nested
    inner class Updates {
        @Test
        fun `on unsafe or inconsistent update metadata verification rejects the release`() = withTestDirectory { root ->
            assumeWindows()
            val mutations = listOf(
                ManifestMutation("http", "https://github.com", "http://github.com", "stable HTTPS GitHub"),
                ManifestMutation("version-url", "/releases/latest/download/", "/releases/download/v1.2.3/", "stable HTTPS GitHub"),
                ManifestMutation("publisher", "Publisher=\"$fixturePublisher\"", "Publisher=\"CN=Other\"", "App Installer Publisher expected"),
                ManifestMutation("background", "<AutomaticBackgroundTask />", "", "Automatic background updates expected"),
                ManifestMutation("activation", "<AutomaticBackgroundTask />", "<AutomaticBackgroundTask /><OnLaunch UpdateBlocksActivation=\"true\" />", "Unsafe App Installer update setting"),
                ManifestMutation("downgrade", "<AutomaticBackgroundTask />", "<AutomaticBackgroundTask /><ForceUpdateFromAnyVersion>true</ForceUpdateFromAnyVersion>", "Unsafe App Installer update setting"),
                ManifestMutation("filename", "/gametier.msix", "/other.msix", "Package URL filename expected"),
            )
            mutations.forEach { mutation ->
                val fixture = UpdateSiteFixture(Files.createDirectory(root.resolve(mutation.name)))
                val metadata = fixture.site.resolve("gametier.appinstaller")
                Files.writeString(metadata, Files.readString(metadata).replace(mutation.from, mutation.to))

                val result = verify(fixture.site, fixture.library)

                assertSoftly {
                    result.first shouldBe 1
                    result.second shouldContain mutation.error
                }
            }
        }
    }
}

private fun assumeWindows() = assumeTrue(System.getProperty("os.name").startsWith("Windows"))

private fun verify(site: Path, library: Path) = runTestPowerShell("-File",
    projectRoot().resolve("desktopApp/packaging/installer/Test-UpdateSite.ps1").toString(),
    "-Site", site.toString(), "-ExplorerLibrary", library.toString(), "-AppVersion", "1.2.3")

private data class ManifestMutation(val name: String, val from: String, val to: String, val error: String)

private class UpdateSiteFixture(root: Path) {
    val site: Path = Files.createDirectory(root.resolve("site"))
    val library: Path = Files.write(root.resolve("GameTierExplorer.dll"), explorerBytes)
    var manifest = """
        <Package xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
          xmlns:com="http://schemas.microsoft.com/appx/manifest/com/windows10"
          xmlns:d4="http://schemas.microsoft.com/appx/manifest/desktop/windows10/4"
          xmlns:d5="http://schemas.microsoft.com/appx/manifest/desktop/windows10/5"
          xmlns:d6="http://schemas.microsoft.com/appx/manifest/desktop/windows10/6"
          xmlns:v="http://schemas.microsoft.com/appx/manifest/virtualization/windows10"
          xmlns:r="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities">
          <Identity Name="GameTier" Publisher="$fixturePublisher" Version="1.2.3.0" ProcessorArchitecture="x64" />
          <Properties>
            <Logo>Assets/logo.png</Logo>
            <d6:FileSystemWriteVirtualization>disabled</d6:FileSystemWriteVirtualization>
            <d6:RegistryWriteVirtualization>disabled</d6:RegistryWriteVirtualization>
            <v:FileSystemWriteVirtualization><v:ExcludedDirectories>
              <v:ExcludedDirectory>${'$'}(KnownFolder:LocalAppData)\GameTierData</v:ExcludedDirectory>
            </v:ExcludedDirectories></v:FileSystemWriteVirtualization>
          </Properties>
          <Applications><Application Id="GameTier" Executable="GameTier.exe"><Extensions>
            <com:Extension Category="windows.comServer"><com:ComServer><com:SurrogateServer>
              <com:Class Id="$fixtureClassId" Path="GameTierExplorer.dll" ThreadingModel="STA" />
            </com:SurrogateServer></com:ComServer></com:Extension>
            <d4:Extension Category="windows.fileExplorerContextMenus"><d4:FileExplorerContextMenus>
              <d5:ItemType Type="*"><d5:Verb Id="Promote" Clsid="$fixtureClassId" /></d5:ItemType>
              <d5:ItemType Type="Directory"><d5:Verb Id="Promote" Clsid="$fixtureClassId" /></d5:ItemType>
            </d4:FileExplorerContextMenus></d4:Extension>
          </Extensions></Application></Applications>
          <Capabilities><r:Capability Name="runFullTrust" /><r:Capability Name="allowElevation" /><r:Capability Name="unvirtualizedResources" /></Capabilities>
        </Package>
    """.trimIndent()
    val entries = linkedMapOf(
        "GameTier.exe" to "MZ\u0000-Dgametier.appPath=&&/../GameTier.exe\u0000".toByteArray(),
        "GameTierExplorer.dll" to explorerBytes,
        "GameTierExplorer.LICENSE.txt" to Files.readAllBytes(projectRoot().resolve("desktopApp/packaging/explorer/third_party/nlohmann/LICENSE.MIT")),
        "LICENSE" to Files.readAllBytes(projectRoot().resolve("LICENSE")),
        "Assets/logo.png" to byteArrayOf(1),
        "AppxSignature.p7x" to byteArrayOf(1),
        "AppxBlockMap.xml" to "<BlockMap />".toByteArray(),
        "app/GameTier.jar" to byteArrayOf(),
    )

    init {
        Files.write(site.resolve("gametier.cer"), Base64.getDecoder().decode(fixtureCertificate))
        Files.write(site.resolve("install.exe"), byteArrayOf(1))
        Files.writeString(site.resolve("download.html"), "<a href=\"install.exe\">Download</a>")
        Files.writeString(site.resolve("metadata.properties"), "version=1.2.3")
        Files.writeString(site.resolve("gametier.appinstaller"), """
            <AppInstaller xmlns="http://schemas.microsoft.com/appx/appinstaller/2018" Uri="$fixtureUpdateUrl/gametier.appinstaller" Version="1.2.3.0">
              <MainPackage Name="GameTier" Publisher="$fixturePublisher" Version="1.2.3.0" ProcessorArchitecture="x64" Uri="$fixtureUpdateUrl/gametier.msix" />
              <UpdateSettings><AutomaticBackgroundTask /></UpdateSettings>
            </AppInstaller>
        """.trimIndent())
        writePackage()
    }

    fun writePackage() {
        entries["AppxManifest.xml"] = manifest.toByteArray()
        ZipOutputStream(Files.newOutputStream(site.resolve("gametier.msix"))).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}

private val explorerBytes = byteArrayOf(0x4d, 0x5a) + ByteArray(64) + "DllGetClassObject".toByteArray()
private const val fixtureClassId = "9B6ED281-384A-4DBF-98C0-D26B6D41B352"
private const val fixturePublisher = "CN=GlobalSign, O=GlobalSign, OU=GlobalSign ECC Root CA - R4"
private const val fixtureUpdateUrl = "https://github.com/bkahlert/gametier/releases/latest/download"
private const val fixtureCertificate = "MIIB3DCCAYOgAwIBAgINAgPlfvU/k/2lCSGypjAKBggqhkjOPQQDAjBQMSQwIgYDVQQLExtHbG9iYWxTaWduIEVDQyBSb290IENBIC0gUjQxEzARBgNVBAoTCkdsb2JhbFNpZ24xEzARBgNVBAMTCkdsb2JhbFNpZ24wHhcNMTIxMTEzMDAwMDAwWhcNMzgwMTE5MDMxNDA3WjBQMSQwIgYDVQQLExtHbG9iYWxTaWduIEVDQyBSb290IENBIC0gUjQxEzARBgNVBAoTCkdsb2JhbFNpZ24xEzARBgNVBAMTCkdsb2JhbFNpZ24wWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAS4xnnTj2wlDp8uORkcA6SumuU5BwkWymOxuYb4ilfBV85C+nOh92VC/x7BALJucw7/xyHlGKSq2XE/qNS5zowdo0IwQDAOBgNVHQ8BAf8EBAMCAYYwDwYDVR0TAQH/BAUwAwEB/zAdBgNVHQ4EFgQUVLB7rUW44kB/+wpu+74zyTyjhNUwCgYIKoZIzj0EAwIDRwAwRAIgIk90crlgr/HmnKAWBVBfw147bmF0774BxL4YSFlhgjICICadVGNA3jdgUM/I2O2dgq43mLyjj0xMqTQrbO/7lZsm"
