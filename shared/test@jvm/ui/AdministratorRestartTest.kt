package com.bkahlert.gametier.ui

import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import org.junit.jupiter.api.Nested

class AdministratorRestartTest {
    @Nested
    inner class Handoff {
        @Test
        fun `on restart the exact selection is restored and temporary arguments are removed`() = withTestDirectory { root ->
            val requests = listOf(request(root), request(root.resolve("second")))
            var argumentFile: Path? = null
            var resumed: RestartSelection? = null
            var acknowledgement: CompletableFuture<Void>? = null
            val restart = AdministratorRestart(root.resolve("javaw.exe"), "classes;library with spaces.jar", root,
                localAppData = root.resolve("Original user 雪").toString()) { executable, file, directory, _ ->
                executable shouldBe root.resolve("javaw.exe")
                directory shouldBe file.parent
                argumentFile = file
                resumed = readRestartJar(file)
                acknowledgement = CompletableFuture.runAsync { resumed!!.acknowledge() }
            }

            val result = restart.restart(requests)
            acknowledgement!!.join()

            assertSoftly {
                result shouldBe Unit
                resumed!!.requests shouldBe requests
                resumed!!.localAppData shouldBe root.resolve("Original user 雪").toString()
                resumed!!.classPath shouldBe listOf(root.resolve("classes"), root.resolve("library with spaces.jar")).joinToString(java.io.File.pathSeparator)
                Files.exists(argumentFile!!) shouldBe false
            }
        }

        @Test
        fun `on a window that never starts the original remains usable with a clear error`() = withTestDirectory { root ->
            var file: Path? = null
            val restart = AdministratorRestart(root.resolve("javaw.exe"), "classes", root,
                startupTimeoutMillis = 1) { _, launchFile, _, _ -> file = launchFile }

            val result = shouldThrow<IOException> { restart.restart(listOf(request(root))) }

            assertSoftly {
                result.message.orEmpty() shouldContain "new GameTier window"
                Files.exists(file!!) shouldBe false
            }
        }

        @Test
        fun `on declined permission and cleanup failure the permission error remains primary`() = withTestDirectory { root ->
            val denied = IOException("Permission was not granted")
            var file: Path? = null
            val restart = AdministratorRestart(root.resolve("javaw.exe"), "classes", root,
                removeLaunchFile = { throw IOException("File in use") }) { _, launchFile, _, _ -> file = launchFile; throw denied }

            val result = shouldThrow<IOException> { restart.restart(listOf(request(root))) }

            assertSoftly {
                result shouldBe denied
                result.suppressed.single().message shouldBe "File in use"
            }
            Files.deleteIfExists(file!!)
        }

        @Test
        fun `on cleanup failure an accepted handoff remains successful`() = withTestDirectory { root ->
            var acknowledgement: CompletableFuture<Void>? = null
            var file: Path? = null
            val restart = AdministratorRestart(root.resolve("javaw.exe"), "classes", root,
                removeLaunchFile = { throw IOException("File in use") }) { _, launchFile, _, selection ->
                file = launchFile
                acknowledgement = CompletableFuture.runAsync { selection.acknowledge() }
            }

            val result = restart.restart(listOf(request(root)))
            acknowledgement!!.join()

            result shouldBe Unit
            Files.deleteIfExists(file!!)
        }

        @Test
        fun `on declined launch no handoff waits and temporary arguments are removed`() = withTestDirectory { root ->
            var argumentFile: Path? = null
            val restart = AdministratorRestart(root.resolve("javaw.exe"), "classes", root) { _, file, _, _ ->
                argumentFile = file
                throw IOException("Permission was not granted")
            }

            val result = shouldThrow<IOException> { restart.restart(listOf(request(root))) }

            assertSoftly {
                result.message shouldBe "Permission was not granted"
                Files.exists(argumentFile!!) shouldBe false
            }
        }

        @Test
        fun `on another client claiming readiness the restart is rejected`() = withTestDirectory { root ->
            var client: CompletableFuture<Void>? = null
            val restart = AdministratorRestart(root.resolve("javaw.exe"), "classes", root) { _, _, _, selection ->
                client = CompletableFuture.runAsync {
                    shouldThrow<IOException> { selection.copy(token = "wrong token").acknowledge() }
                }
            }

            val result = shouldThrow<IOException> { restart.restart(listOf(request(root))) }
            client!!.join()

            result.message.orEmpty() shouldContain "acknowledge"
        }

        @Test
        fun `on normal startup no selection is restored`() {
            val result = RestartSelection.parse(emptyArray())

            result shouldBe null
        }

        @Test
        fun `on malformed resume arguments startup is rejected`() {
            for (arguments in listOf(arrayOf("--resume-promotion"), arrayOf("unknown"),
                arrayOf("--resume-promotion", "one", "games", "--ready", "0", "token"))) {
                val result = shouldThrow<IllegalArgumentException> { RestartSelection.parse(arguments) }

                result.message.orEmpty() shouldContain "restart"
            }
        }
    }

    @Nested
    inner class WindowsLaunch {
        @Test
        fun `on UAC cancellation the error explains that the app stays open`() {
            val launcher = WindowsElevation(Path.of("powershell.exe")) { 1223 }

            val result = shouldThrow<IOException> { launcher.launch(Path.of("javaw.exe"), Path.of("arguments"), Path.of("work")) }

            result.message.orEmpty() shouldContain "Permission was not granted"
        }

        @Test
        fun `on failed launch the native error code is retained`() {
            val launcher = WindowsElevation(Path.of("powershell.exe")) { 5 }

            val result = shouldThrow<IOException> { launcher.launch(Path.of("javaw.exe"), Path.of("arguments"), Path.of("work")) }

            result.message.orEmpty() shouldContain "5"
        }

        @Test
        fun `on special characters paths are literal and elevation requires UAC`() {
            var command = emptyList<String>()
            val launcher = WindowsElevation(Path.of("powershell.exe")) { command = it; 0 }

            val result = launcher.launch(Path.of("C:/JDK's/javaw.exe"), Path.of("C:/temp/a & b.jar"), Path.of("C:/work's"))
            val script = String(java.util.Base64.getDecoder().decode(command.last()), Charsets.UTF_16LE)

            assertSoftly {
                result shouldBe Unit
                command.first() shouldBe "powershell.exe"
                script shouldContain "JDK''s"
                script shouldContain "a & b"
                script shouldContain "-Verb RunAs"
                script shouldContain "-WindowStyle Hidden"
            }
        }
    }

    private fun request(root: Path): TransferRequest {
        val original = root.resolve("Game's \$special & 雪")
        val directory = root.resolve("fast games")
        return TransferRequest(TransferKind.PROMOTE, original, directory, directory.resolve(original.fileName))
    }
}
