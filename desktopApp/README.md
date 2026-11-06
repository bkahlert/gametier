# Development

Run these commands from the repository root on Windows:

```powershell
.\kotlin.bat build
.\kotlin.bat run -m desktopApp --jvm-args=--enable-native-access=ALL-UNNAMED
.\kotlin.bat test
```

The wrapper downloads the pinned [Kotlin Toolchain](https://kotlin-toolchain.org/dev/)
on first use. Add `-m shared` to the test command to test only the shared module.
The IDE's run configurations can also launch the app.

The desktop entry point is [main.kt](src/main.kt). The [shared module](../shared)
contains the UI, game transfers, filesystem operations, and settings; tests mirror
the source packages.

## Filesystem integration tests

The NTFS/ReFS suite provisions disposable volumes and uses UAC for the phases
that require elevation. From an **unelevated** PowerShell, read the runner's help
before running it:

```powershell
Get-Help .\scripts\Test-Filesystems.ps1 -Full
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Filesystems.ps1
```

See [Test-Filesystems.ps1](../scripts/Test-Filesystems.ps1) for prerequisites,
existing-volume usage, and cleanup guidance.

## Releases

Push a `vMAJOR.MINOR.PATCH` tag, such as `v0.1.1`, to trigger the
[release workflow](../.github/workflows/release.yml). It runs tests, stages the
application JARs, and packages a Windows x64 MSI with a bundled Java runtime.
Current installers are unsigned.
The Explorer action appears in the classic context menu (Windows 11: **Show more
options**). Windows 11's primary menu requires a signed package identity; see
[Explorer packaging](packaging/explorer/README.md).

To stage the same JARs locally:

```powershell
.\kotlin.bat do stageInstaller -m desktopApp
```

Build the [native Explorer command](packaging/explorer/README.md), then package
with JDK 25 and WiX available on `PATH`:

```powershell
.\desktopApp\packaging\installer\Build-Installer.ps1 -AppVersion 0.1.1
```

CI installs two test versions on its clean runner, checks registration and a
disabled preference through upgrade, then uninstalls. See
[installer scripts](packaging/installer/README.md) for packaging checks and the
separate signed identity preparation path.
