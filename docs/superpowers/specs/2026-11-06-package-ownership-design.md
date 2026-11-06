# Package ownership

Approved in the architecture review on 2026-11-06.

Keep the existing build modules and common/JVM source sets. Add responsibility
directories beneath each source root, with matching Kotlin package suffixes and
matching test directories:

| Package | Ownership |
| --- | --- |
| `game` | Promotion, demotion, recovery, and transfer-specific test fixtures |
| `files` | Independent file moves, symlink creation, and their test assertions |
| `settings` | Preferences, persistence, validation, and settings test fixtures |
| `ui` | Compose entry and the existing greeting/platform scaffold |
| `testsupport.filesystem` | Shared cleanup, filesystem examples, symlink prerequisites, and volume matrix |
| `testsupport.workflow` | Project lookup, PowerShell execution, privilege checks, and script workflow tests |

Each package uses the `com.bkahlert.gametier` prefix. Keep the desktop launcher
and generated resource package unchanged. Move expect/actual declarations
together. Packages express ownership; `internal` visibility still spans the
compiled `shared` module.

Split script execution support out of `FilesystemFixtures.kt`. Move shared
symlink assertions from `SymlinkCreatorTest.kt` into a files-owned fixture.
Keep fixtures used by one feature beside that feature's tests. Retain the
existing fixture interfaces, NTFS/ReFS matrix, symlink tags, and fault-injection
seam. Do not consolidate filesystem entry policy in this change.

Preserve behavior and test cases. Compare baseline and final test discovery,
build both modules, and run the existing tests. Report unmet filesystem and
symlink prerequisites without silently reducing coverage.
