package com.bkahlert.gametier.testsupport.workflow

import java.nio.file.Files
import java.nio.file.Path

internal fun projectRoot(): Path {
    var path: Path? = Path.of("").toAbsolutePath()
    while (path != null && !Files.exists(path.resolve("project.yaml"))) path = path.parent
    return requireNotNull(path) { "Cannot locate project.yaml" }
}

internal fun runTestPowerShell(vararg arguments: String): Pair<Int, String> {
    val process = ProcessBuilder(
        listOf("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass") +
            arguments,
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    return process.waitFor() to output
}

internal val isWindowsElevated: Boolean by lazy {
    val result = runTestPowerShell(
        "-Command",
        "\$identity = [Security.Principal.WindowsIdentity]::GetCurrent(); " +
            "\$principal = New-Object Security.Principal.WindowsPrincipal(\$identity); " +
            "\$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)",
    )
    check(result.first == 0) { result.second }
    result.second.trim().lowercase().toBooleanStrict()
}
