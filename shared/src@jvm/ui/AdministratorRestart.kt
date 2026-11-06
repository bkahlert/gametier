package com.bkahlert.gametier.ui

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

class AdministratorRestart internal constructor(
    private val java: Path,
    private val classPath: String,
    private val workingDirectory: Path,
    private val localAppData: String? = System.getProperty("gametier.localAppData") ?: System.getenv("LOCALAPPDATA"),
    private val removeLaunchFile: (Path) -> Unit = { Files.deleteIfExists(it) },
    private val startupTimeoutMillis: Int = 60_000,
    private val launch: (Path, Path, Path, RestartSelection) -> Unit,
) {
    constructor() : this(
        Path.of(System.getProperty("java.home"), "bin", "javaw.exe"),
        System.getProperty("gametier.classPath") ?: System.getProperty("java.class.path"),
        Path.of("").toAbsolutePath(),
        launch = { executable, arguments, directory, _ -> WindowsElevation().launch(executable, arguments, directory) },
    )

    fun restart(request: TransferRequest) {
        require(request.kind == TransferKind.PROMOTE) { "Only an unstarted promotion can be reopened" }
        var accepted = false
        try { ServerSocket(0, 1, loopback).use { server ->
            server.soTimeout = startupTimeoutMillis
            val absoluteClassPath = classPath.split(File.pathSeparator).joinToString(File.pathSeparator) {
                workingDirectory.resolve(it).normalize().toString()
            }
            val selection = RestartSelection(request, server.localPort, UUID.randomUUID().toString(), localAppData, absoluteClassPath)
            val file = Files.createTempFile("gametier-restart-", ".jar")
            var failure: Exception? = null
            try {
                writeRestartJar(file, selection, workingDirectory)
                launch(java, file, file.parent, selection)
                server.accept().use { client ->
                    client.soTimeout = 5_000
                    if (DataInputStream(client.getInputStream()).readUTF() != selection.token) {
                        throw IOException("The new GameTier window did not acknowledge the restart")
                    }
                    DataOutputStream(client.getOutputStream()).apply { writeUTF("ready"); flush() }
                    accepted = true
                }
            } catch (error: Exception) {
                failure = error
                throw error
            } finally {
                try { removeLaunchFile(file) } catch (error: Exception) {
                    failure?.addSuppressed(error)
                    try { file.toFile().deleteOnExit() } catch (_: Exception) { }
                }
            }
        } } catch (error: Exception) {
            if (!accepted) throw if (error is SocketTimeoutException)
                IOException("The new GameTier window did not start in time. GameTier stays open; you can try again.", error) else error
        }
    }
}

data class RestartSelection(val request: TransferRequest, val port: Int, val token: String,
    val localAppData: String? = null, val classPath: String? = null) {
    internal fun arguments(): Array<String> = arrayOf(
        "--resume-promotion", request.original.toString(), request.directory.toString(), "--ready", port.toString(), token,
    )

    fun acknowledge() {
        Socket().use { client ->
            client.connect(java.net.InetSocketAddress(loopback, port), 5_000)
            client.soTimeout = 5_000
            DataOutputStream(client.getOutputStream()).apply { writeUTF(token); flush() }
            if (DataInputStream(client.getInputStream()).readUTF() != "ready") throw IOException("Restart was not accepted")
        }
    }

    companion object {
        fun parse(arguments: Array<String>): RestartSelection? {
            if (arguments.isEmpty()) return null
            if (arguments.contentEquals(arrayOf("--restart"))) return readRestartJar(Path.of(System.getProperty("java.class.path")))
            require(arguments.size == 6 && arguments[0] == "--resume-promotion" && arguments[3] == "--ready") { "Invalid restart arguments" }
            val port = arguments[4].toIntOrNull()
            require(port != null && port in 1..65535 && arguments[5].isNotBlank()) { "Invalid restart acknowledgement" }
            val original = Path.of(arguments[1])
            val directory = Path.of(arguments[2])
            require(original.isAbsolute && directory.isAbsolute && original.fileName != null) { "Invalid restart selection" }
            return RestartSelection(TransferRequest(TransferKind.PROMOTE, original, directory, directory.resolve(original.fileName)), port, arguments[5])
        }
    }
}

internal class WindowsElevation(
    private val powershell: Path = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe"),
    private val execute: (List<String>) -> Int = { command ->
        ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
    },
) {
    fun launch(executable: Path, arguments: Path, directory: Path) {
        val script = """
            try {
                Start-Process -FilePath ${literal(executable.toString())} -ArgumentList ${literal("-jar \"${arguments.fileName}\" --restart")} -WorkingDirectory ${literal(directory.toString())} -Verb RunAs -WindowStyle Hidden -PassThru -ErrorAction Stop | Out-Null
                exit 0
            } catch {
                ${'$'}failure = ${'$'}_.Exception
                while (${'$'}null -ne ${'$'}failure) {
                    if (${'$'}failure -is [ComponentModel.Win32Exception]) { exit ${'$'}failure.NativeErrorCode }
                    ${'$'}failure = ${'$'}failure.InnerException
                }
                exit 1
            }
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val result = execute(listOf(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", encoded))
        when (result) {
            0 -> Unit
            1223 -> throw IOException("Permission was not granted. GameTier stays open; you can try again.")
            else -> throw IOException("Windows could not restart GameTier as administrator (error $result). GameTier stays open.")
        }
    }

    private fun literal(value: String): String = "'${value.replace("'", "''")}'"
}

private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

internal fun writeRestartJar(file: Path, selection: RestartSelection, workingDirectory: Path,
    mainClass: String = "com.bkahlert.gametier.MainKt") {
    val classPath = requireNotNull(selection.classPath)
    val manifest = Manifest().apply {
        mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        mainAttributes[Attributes.Name.MAIN_CLASS] = mainClass
        mainAttributes[Attributes.Name.CLASS_PATH] = classPath.split(File.pathSeparator).joinToString(" ") {
            workingDirectory.resolve(it).normalize().toUri().toASCIIString()
        }
        mainAttributes.putValue("GameTier-Selection", encode(selection.arguments().joinToString("\u0000")))
        mainAttributes.putValue("GameTier-ClassPath", encode(classPath))
        selection.localAppData?.let { mainAttributes.putValue("GameTier-LocalAppData", encode(it)) }
    }
    JarOutputStream(Files.newOutputStream(file), manifest).close()
}

internal fun readRestartJar(file: Path): RestartSelection = JarFile(file.toFile()).use { jar ->
    val attributes = jar.manifest.mainAttributes
    val selection = requireNotNull(RestartSelection.parse(decode(attributes.getValue("GameTier-Selection")).split('\u0000').toTypedArray()))
    selection.copy(localAppData = attributes.getValue("GameTier-LocalAppData")?.let(::decode),
        classPath = decode(attributes.getValue("GameTier-ClassPath")))
}

private fun encode(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
private fun decode(value: String): String = String(Base64.getDecoder().decode(value), Charsets.UTF_8)
