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
application JARs, and uses Conveyor to produce a self-updating Windows x64
EXE/MSIX distribution with a bundled Java runtime. A self-signed certificate
provides a stable package identity and Explorer integration without a paid
signing service. See [installer packaging](packaging/installer/README.md) for
the one-time release-key setup and local verification.

To stage the same JARs locally:

```powershell
.\kotlin.bat do stageInstaller -m desktopApp
```

Build the [native Explorer command](packaging/explorer/README.md), then package
from an elevated PowerShell with Conveyor 22.3 available on `PATH`:

```powershell
.\desktopApp\packaging\installer\Build-UpdateSite.ps1 -AppVersion 0.1.1
```

CI builds two test versions with a disposable key. A clean Windows runner checks
the packages, settings retention, symlinks, deferred updates, and uninstall.
