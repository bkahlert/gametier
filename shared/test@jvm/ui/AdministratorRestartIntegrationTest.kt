package com.bkahlert.gametier.ui

import com.bkahlert.gametier.testsupport.filesystem.withTestDirectory
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class AdministratorRestartIntegrationTest {
    @Test
    fun `on a JVM restart Unicode and command characters survive argument parsing`() = withTestDirectory { root ->
        val classes = Files.createDirectory(root.resolve("Pokémon 雪"))
        val output = classes.resolve("result.txt")
        val source = Files.writeString(
            root.resolve("Arguments.java"),
            // language=Java
            """
            class Arguments {
                public static void main(String[] args) throws Exception {
                    try (var jar = new java.util.jar.JarFile(System.getProperty("java.class.path"))) {
                        var values = jar.getManifest().getMainAttributes();
                        var payload = new String(java.util.Base64.getDecoder().decode(values.getValue("GameTier-Selection")), java.nio.charset.StandardCharsets.UTF_8);
                        var output = java.nio.file.Path.of(new String(java.util.Base64.getDecoder().decode(values.getValue("GameTier-LocalAppData")), java.nio.charset.StandardCharsets.UTF_8));
                        java.nio.file.Files.writeString(output, payload);
                    }
                }
            }
             """.trimIndent()
        )
        val compilerExit = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString())
        compilerExit shouldBe 0
        val original = root.resolve("Pokémon 雪 & Game's \$special")
        val directory = root.resolve("fast games")
        val selection = RestartSelection(listOf(TransferRequest(TransferKind.PROMOTE, original, directory, directory.resolve(original.fileName)),
            TransferRequest(TransferKind.PROMOTE, root.resolve("second"), root.resolve("other"), root.resolve("other/second"))),
            1234, "token", output.toString(), classes.toString())
        val jar = root.resolve("restart.jar")
        writeRestartJar(jar, selection, root, "Arguments")
        val java = Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        )

        val process = ProcessBuilder(java.toString(), "-jar", jar.fileName.toString(), "--restart").directory(root.toFile()).redirectErrorStream(true).start()
        val diagnostic = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        val result = if (exit == 0) Files.readString(output) else diagnostic

        assertSoftly {
            exit shouldBe 0
            result shouldBe selection.arguments().joinToString("\u0000")
        }
    }
}
