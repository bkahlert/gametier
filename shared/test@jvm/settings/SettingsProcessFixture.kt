package com.bkahlert.gametier.settings

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal suspend fun <T> withSettingsProcess(file: Path, action: suspend (SettingsProcess) -> T): T {
    val classPath = (System.getProperty("java.class.path").split(File.pathSeparator) +
        generateSequence(Thread.currentThread().contextClassLoader) { it.parent }
            .filterIsInstance<URLClassLoader>().flatMap { it.urLs.asSequence() }.map { Path.of(it.toURI()).toString() })
        .distinct().joinToString(File.pathSeparator)
    val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
    val process = ProcessBuilder(java.toString(), "--enable-native-access=ALL-UNNAMED", "-cp", classPath,
        SettingsProcessFixture::class.java.name, file.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val fixture = SettingsProcess(process)
    try {
        check(fixture.response() == "ready")
        return action(fixture)
    } finally {
        process.destroyForcibly()
        withContext(NonCancellable + Dispatchers.IO) {
            check(process.waitFor(10, SECONDS))
            fixture.close()
        }
    }
}

internal class SettingsProcess(private val process: Process) : AutoCloseable {
    private val output = process.inputStream.bufferedReader()
    private val input = process.outputStream.bufferedWriter()
    suspend fun request(command: String): String {
        input.write("$command\n")
        input.flush()
        return response()
    }
    suspend fun response(): String = runInterruptible(Dispatchers.IO) {
        CompletableFuture.supplyAsync { output.readLine() }.get(10, SECONDS) ?: error("Settings process exited")
    }
    override fun close() { input.close(); output.close() }
}

internal object SettingsProcessFixture {
    @JvmStatic
    fun main(arguments: Array<String>) = runBlocking {
        val file = Path.of(arguments.single())
        withSettingsStore(file) { settings ->
            settings.defaultPromotionFolder.first()
            settings.promotionFolders.first()
            println("ready")
            System.out.flush()
            val input = System.`in`.bufferedReader()
            while (true) {
                when (input.readLine() ?: break) {
                    "hold" -> {
                        SettingsCoordinator(file).lock {
                            println("held")
                            System.out.flush()
                            check(input.readLine() == "release")
                        }
                        println("released")
                    }
                    "save" -> {
                        settings.setDefaultPromotionFolder(file.parent.resolve("Child default").toString())
                        settings.rememberPromotionFolder(file.parent.resolve("Child history").toString())
                        println("saved")
                    }
                    "read" -> println("${settings.defaultPromotionFolder.first()}|${settings.promotionFolders.first()
                        .map { Path.of(it).fileName.toString() }.sorted().joinToString(",")}")
                    else -> error("Unknown command")
                }
                System.out.flush()
            }
        }
    }
}
