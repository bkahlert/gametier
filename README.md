**Mission:** Help users move a game file or directory from slower storage to faster storage, freeing space on the original drive while keeping the game accessible to existing programs at its original path. Windows is the first target; other platforms may follow.

This is a Kotlin Multiplatform project targeting Desktop (JVM), built with
the [Kotlin Toolchain](https://kotlin-toolchain.org/dev/).

- [/desktopApp](./desktopApp) contains the desktop (JVM) application.
- [/shared](./shared) holds the code shared across your applications — Compose UI, business logic, and platform-specific
  implementations. [src](./shared/src) is for common code; the sibling `src@<platform>` folders (for example
  `src@android`) hold code compiled only for the platform named in the folder.

The `kotlin` (macOS/Linux) and `kotlin.bat` (Windows) scripts in the project root are self-bootstrapping wrappers for
the Kotlin Toolchain: they download the pinned toolchain version on first use, so no separate installation is required.
Build the whole project with `./kotlin build`.

### Running

Use the run configurations provided by the run widget in your IDE's toolbar. You can also run each module from the
command line:

- Desktop app: `./kotlin run -m desktopApp`

### Testing

Run the project's tests with `./kotlin test`, or `./kotlin test -m <module>` for a single module.

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
junctions; Windows must permit symlink creation for the calling user. Moving and
link creation remain separate operations.

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
`GAMETIER_TEST_VOLUMES` even on failure. Both integration suites share volume
validation, pairings, and per-test directories in `TestVolumes.kt`.
`Test-FileMoves.ps1` remains a compatibility entry point to the shared runner.

---

Learn more
about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html), [Kotlin Toolchain](https://kotlin-toolchain.org/dev/), [Compose Multiplatform](https://kotlinlang.org/compose-multiplatform/).
