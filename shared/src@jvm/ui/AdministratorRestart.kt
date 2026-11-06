package com.bkahlert.gametier.ui

import com.bkahlert.gametier.files.packagedApplicationPath
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
    constructor() : this(defaultAdministratorLaunch())

    private constructor(administratorLaunch: AdministratorLaunch) : this(
        administratorLaunch.executable,
        System.getProperty("gametier.classPath") ?: System.getProperty("java.class.path"),
        Path.of("").toAbsolutePath(),
        launch = { executable, arguments, directory, _ ->
            WindowsElevation().launch(executable, administratorLaunch.arguments(arguments), directory)
        },
    )

    fun restart(requests: List<TransferRequest>) {
        var accepted = false
        try { ServerSocket(0, 1, loopback).use { server ->
            server.soTimeout = startupTimeoutMillis
            val absoluteClassPath = classPath.split(File.pathSeparator).joinToString(File.pathSeparator) {
                workingDirectory.resolve(it).normalize().toString()
            }
            val selection = RestartSelection(requests, server.localPort, UUID.randomUUID().toString(), localAppData, absoluteClassPath)
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

data class RestartSelection(val requests: List<TransferRequest>, val port: Int, val token: String,
    val localAppData: String? = null, val classPath: String? = null) {
    init {
        require(requests.isNotEmpty() && requests.all { it.kind == TransferKind.PROMOTE }) {
            "Only unstarted promotions can be reopened"
        }
    }

    internal fun arguments(): Array<String> = buildList {
        requests.forEach { addAll(listOf("--resume-promotion", it.original.toString(), it.directory.toString())) }
        addAll(listOf("--ready", port.toString(), token))
    }.toTypedArray()

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
            if (arguments.size == 2 && arguments[0] == "--restart") return readRestartJar(Path.of(arguments[1]))
            require(arguments.size >= 6 && arguments.size % 3 == 0 && arguments[arguments.size - 3] == "--ready") { "Invalid restart arguments" }
            val port = arguments[arguments.size - 2].toIntOrNull()
            val token = arguments.last()
            require(port != null && port in 1..65535 && token.isNotBlank()) { "Invalid restart acknowledgement" }
            val requests = arguments.dropLast(3).chunked(3).map { selection ->
                require(selection[0] == "--resume-promotion") { "Invalid restart arguments" }
                val original = Path.of(selection[1])
                val directory = Path.of(selection[2])
                require(original.isAbsolute && directory.isAbsolute && original.fileName != null) { "Invalid restart selection" }
                TransferRequest(TransferKind.PROMOTE, original, directory, directory.resolve(original.fileName))
            }
            return RestartSelection(requests, port, token)
        }
    }
}

internal class WindowsElevation(
    private val powershell: Path = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe"),
    private val execute: (List<String>) -> Int = { command ->
        ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
    },
) {
    fun launch(executable: Path, arguments: String, directory: Path) {
        val script = """
            try {
                Start-Process -FilePath ${literal(executable.toString())} -ArgumentList ${literal(arguments)} -WorkingDirectory ${literal(directory.toString())} -Verb RunAs -WindowStyle Hidden -PassThru -ErrorAction Stop | Out-Null
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

internal data class AdministratorLaunch(val executable: Path, val packaged: Boolean) {
    fun arguments(file: Path): String = if (packaged) "--restart \"${file.toAbsolutePath()}\"" else "-jar \"${file.fileName}\" --restart"
}

internal fun defaultAdministratorLaunch(
    appPath: Path? = packagedApplicationPath(),
    javaHome: String = System.getProperty("java.home"),
): AdministratorLaunch = appPath?.let {
    AdministratorLaunch(it, true)
} ?: AdministratorLaunch(Path.of(javaHome, "bin", "javaw.exe"), false)

private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")

internal fun writeRestartJar(file: Path, selection: RestartSelection, workingDirectory: Path,
    mainClass: String = "com.bkahlert.gametier.MainKt") {
    val classPath = requireNotNull(selection.classPath)
    val manifest = Manifest().apply {
        mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        mainAttributes[Attributes.Name.MAIN_CLASS] = mainClass
        mainAttributes.putValue("Enable-Native-Access", "ALL-UNNAMED")
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
