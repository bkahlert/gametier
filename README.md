# GameTier [![CI](https://github.com/bkahlert/gametier/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/bkahlert/gametier/actions/workflows/ci.yml) [![Buy Me A Coffee](https://img.shields.io/static/v1?label=&message=%E2%98%95%20Buy%20Me%20A%20Coffee&color=FFDD00)](https://www.buymeacoffee.com/bkahlert)

**Mission:** Help users move a game file or directory from slower storage to faster storage, freeing space on the original drive while keeping the game accessible to existing programs at its original path. Windows is the first target; other platforms may follow.

This is a Kotlin Multiplatform project targeting Desktop (JVM), built with
the [Kotlin Toolchain](https://kotlin-toolchain.org/dev/).

- [/desktopApp](./desktopApp) contains the desktop (JVM) application.
- [/shared](./shared) holds the code shared across your applications — Compose UI, business logic, and platform-specific
  implementations. [src](./shared/src) is for common code; the sibling `src@<platform>` folders (for example
  `src@android`) hold code compiled only for the platform named in the folder.

Sources are grouped by responsibility inside the existing source roots. Kotlin
packages use the prefix `com.bkahlert.gametier`; tests mirror the source packages.

| Package | Responsibility | Sources |
| --- | --- | --- |
| `game` | Promotion, demotion, and recovery | [JVM](./shared/src@jvm/game) |
| `files` | Independent file moves and symlink creation | [JVM](./shared/src@jvm/files) |
| `settings` | Preferences, validation, and persistence | [Common](./shared/src/settings), [JVM](./shared/src@jvm/settings) |
| `ui` | Compose entry and greeting/platform scaffold | [Common](./shared/src/ui), [JVM](./shared/src@jvm/ui) |

Feature fixtures live with their tests. Reusable [filesystem test support](./shared/test@jvm/testsupport/filesystem)
owns cleanup, symlink prerequisites, and the volume matrix; [workflow test support](./shared/test@jvm/testsupport/workflow)
owns PowerShell execution, privilege checks, and script workflow tests.

The `kotlin` (macOS/Linux) and `kotlin.bat` (Windows) scripts in the project root are self-bootstrapping wrappers for
the Kotlin Toolchain: they download the pinned toolchain version on first use, so no separate installation is required.
Build the whole project with `./kotlin build`.

### Running

Use the run configurations provided by the run widget in your IDE's toolbar. You can also run each module from the
command line:

- Desktop app: `./kotlin run -m desktopApp`

### Testing

Run the project's tests with `./kotlin test`, or `./kotlin test -m <module>` for a single module.

### Settings

`getAppSettings()` supplies a shared `AppSettings` instance backed by AndroidX
Preferences DataStore. Its `defaultPromotionFolder: Flow<String?>` emits the
current preference and changes. Use suspending `setDefaultPromotionFolder(path)`
and `clearDefaultPromotionFolder()` to update it; read once with Flow's `first()`.
The preference starts unset and represents the parent folder for promoted games.

The Windows backend stores per-user settings in
`%LOCALAPPDATA%\GameTier\settings.preferences_pb`. It accepts native absolute paths
without requiring an available drive or existing folder and preserves their text.
Invalid paths and storage errors are reported; successful writes are persisted.
Use one running GameTier process at a time. UI and settings integration with
promotion follow separately.

### Filesystem components

The JVM building block is `FileMover.move(source: Path, destination: Path)`.
It renames within a volume, or copies files and directory trees across volumes,
checks entries and file sizes, then deletes the source. The destination parent
must exist; existing destinations are rejected rather than overwritten or merged.
Stop programs writing to the source before moving it.

Normal timestamps and supported DOS attributes are retained; cross-volume
permissions inherit from the destination. There is no custom ACL/SACL preservation,
hashing, or rollback. The mover rejects symbolic links, junctions, and other reparse
points. This is not a lossless backup of every filesystem feature.
Copy/verification failures retain the source and may leave a partial destination;
cleanup failures leave the copied destination and possibly some source entries.
Both failures throw a contextual `IOException`.

`SymlinkCreator.create(link: Path, target: Path)` creates a file or directory
symbolic link, including across drives. It stores an absolute target and accepts
relative inputs and parent-directory aliases. The target must exist and must not
be a link, junction, special entry, or filesystem root. The link parent must exist;
occupied locations and directory links inside their targets are rejected. Target
directory contents are not scanned. Failures throw a contextual `IOException`
preserving the cause. The creator does not elevate privileges or fall back to
junctions; Windows must permit symlink creation for the calling user. The creator
can also be used independently of game transfers.

`Promoter.promote(original: Path, destination: Path)` moves a game file (such as
a ROM) or directory to the complete destination path and leaves an absolute
symlink at its original location. `Demoter.demote(original: Path)` replaces that
symlink with its target. Demotion also accepts externally created symlinks with
absolute or relative targets, but rejects dangling links, chains, and junctions.
Both operations are blocking and support same-volume and cross-volume transfers.

Promotion checks symlink support with a temporary sibling link before moving.
Both operations validate before relocating data and attempt recovery when safe.
Move failures preserve partial copies; ambiguous copies are never deleted or
merged automatically. Failures report the phase and paths in an `IOException`,
with the original cause and suppressed recovery errors. A failed preflight
cleanup reports the leftover link. Inspect reported paths before retrying.
Stop game writes and serialize operations on the same game. There is no crash
recovery journal, automatic elevation, or progress/cancellation interface.

Run ordinary tests on Windows with `.\kotlin.bat test -m shared`. Real NTFS/ReFS
integration tests are opt-in:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Filesystems.ps1
```

Run this command **unelevated**. It requests UAC consent for setup, cleanup, and a
separate successful-symlink test phase. General tests, move tests, and symlink
failure tests run with your normal permissions. Only tests tagged `symlink-success`
run elevated; that phase requires symlink support and fails if creation is
unavailable. No Developer Mode or user-rights settings are changed.
The workflow creates two NTFS and two
ReFS disposable VHDX images and covers NTFS-to-NTFS, NTFS-to-ReFS, ReFS-to-ReFS,
ReFS-to-NTFS, same-volume moves and symlinks, collisions, and move-specific
locked-file failures. Each symlink volume/pairing has its own test result.
When running ordinary tests directly, successful-symlink tests report an unmet
prerequisite if the calling user cannot create symlinks. Cleanup is
attempted even after failures; any leftover fixture directory is reported.
Only newly created image disks are formatted; existing volumes are never formatted.

Images are dynamically expanding, with a 64 GiB virtual capacity each (256 GiB
total); actual disk use grows with formatting and test data. ReFS creation requires
a Windows edition/build that supports it. Where available, the helper uses
Dev Drive formatting for the ReFS fixtures; it does not change global antivirus
or Dev Drive policies. Hyper-V and third-party RAM-disk drivers are not required.
Unattended CI must provision elevated setup/cleanup separately and run the tests
as an ordinary user; a UAC prompt cannot be answered noninteractively.

To use four existing volumes instead, provide writable roots in that same order:

```powershell
.\scripts\Test-Filesystems.ps1 -ExistingVolumes 'F:\tests','G:\tests','H:\tests','I:\tests'
```

This mode creates and removes only uniquely named test directories within those
roots. Tests validate that the roots refer to four distinct volumes with the
expected filesystem types. Alternatively, set `GAMETIER_TEST_VOLUMES` to the four
semicolon-separated roots before running the shared tests.

The reusable `scripts/TestVolumes.psm1` facility exports
`Invoke-WithTestVolumes -Action { ... }`, with optional `-ExistingVolumes`.
It provisions and cleans up fixtures around the action and restores
`GAMETIER_TEST_VOLUMES` even on failure. All filesystem suites share volume
validation, pairings, and per-test directories in
[`TestVolumes.kt`](./shared/test@jvm/testsupport/filesystem/TestVolumes.kt).
`Test-FileMoves.ps1` remains a compatibility entry point to the shared runner.

---

Learn more
about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html), [Kotlin Toolchain](https://kotlin-toolchain.org/dev/), [Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/).
