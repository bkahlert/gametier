package com.bkahlert.gametier.game

import com.bkahlert.gametier.settings.DataStoreAppSettings
import com.bkahlert.gametier.settings.MemoryPreferencesStore
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.IOException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS
import kotlinx.coroutines.runBlocking

internal fun <T> withLockedManagerProcess(root: Path, operation: String, action: (Path) -> T): T {
    val localAppData = root.resolve("Original user")
    val file = Files.createDirectories(localAppData.resolve("GameTier")).resolve("transfers.lock")
    val classPath = (System.getProperty("java.class.path").split(File.pathSeparator) +
        generateSequence(Thread.currentThread().contextClassLoader) { it.parent }
            .filterIsInstance<URLClassLoader>().flatMap { it.urLs.asSequence() }.map { Path.of(it.toURI()).toString() })
        .distinct().joinToString(File.pathSeparator)
    val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
    val process = ProcessBuilder(java.toString(), "--enable-native-access=ALL-UNNAMED",
        "-Dgametier.localAppData=$localAppData", "-cp", classPath,
        TransferLockProcessFixture::class.java.name, root.toString(), operation)
        .apply { environment()["LOCALAPPDATA"] = root.resolve("Administrator").toString() }
        .redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader()
    val input = process.outputStream.bufferedWriter()
    try {
        CompletableFuture.supplyAsync { output.readLine() }.get(20, SECONDS) shouldBe "entered"
        return action(file)
    } finally {
        input.write("release\n")
        input.flush()
        try {
            process.waitFor(20, SECONDS) shouldBe true
            process.exitValue() shouldBe 0
        } finally {
            process.destroyForcibly()
            input.close()
            output.close()
        }
    }
}

internal object TransferLockProcessFixture {
    @JvmStatic
    fun main(arguments: Array<String>) = runBlocking {
        val root = Path.of(arguments[0])
        val settings = DataStoreAppSettings(MemoryPreferencesStore())
        fun handshake() {
            println("entered")
            System.out.flush()
            check(CompletableFuture.supplyAsync { System.`in`.bufferedReader().readLine() }.get(20, SECONDS) == "release")
        }
        when (arguments[1]) {
            "inventory" -> PromotionManager(settings, BackupStore({ root.resolve("backups") }, {
                handshake()
                emptyList()
            })).inventory()
            "promote" -> try {
                PromotionManager(settings).promote(root.resolve("game"), root.resolve("destination")) {
                    handshake()
                    throw IOException("stop transfer")
                }
                error("Transfer should fail")
            } catch (expected: IOException) {
                check(expected.message == "stop transfer")
            }
            else -> error("Unknown operation")
        }
        Unit
    }
}
