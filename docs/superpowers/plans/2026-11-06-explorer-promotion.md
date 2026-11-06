# Explorer Promotion Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development to implement and review each task.

**Goal:** Open promotion review from Explorer without treating UAC requirements as ineligibility.

**Architecture:** A native shell command performs lightweight eligibility checks and starts the app with a validated path. Application review and execution enforce the same managed-directory boundary. Installer and Settings own the per-user registration lifecycle.

**Tech Stack:** Kotlin Toolchain, Compose Desktop, JNA, native Windows COM, CMake/MSVC, PowerShell and jpackage.

**Spec:** `docs/superpowers/specs/2026-11-06-explorer-promotion-design.md`

## Global Constraints

- Source roots and tests retain the `game`, `files`, `settings`, `ui` responsibilities.
- No menu check may test symlink-creation privilege or prompt for UAC.
- No recursive scans or JVM startup during menu construction.
- No certificate trust or Developer Mode changes.
- Keep unsigned MSI distribution working; primary-menu identity needs release signing.

### Task 1: Managed source boundary

Files: add `shared/src@jvm/game/PromotionEligibility.kt` and mirrored tests; modify `ui/FileSelectionPreparer.kt` and `game/PromotionManager.kt` with regression tests.

- [x] Write and run failing boundary tests: marker in selected directory, parent and distant ancestor; malformed marker; no marker; missing/unreadable ancestors and alias paths.
- [x] Implement `PromotionEligibility.check(Path): String?` with metadata reads, no mutation or symlink privilege probe. Return a contextual blocker or null.
- [x] Enforce it during review and again before transfer registration/mutation. Preserve existing indexed-selection diagnostics.
- [x] Run the shared test suite, including a ready-for-review test with symlink creation unavailable.

### Task 2: Native shell command and package assets

Files: create `desktopApp/packaging/explorer/` CMake project, COM command, native tests and sparse-package manifest.

- [x] Write command tests for one file/folder, zero/multiple selections, roots, reparse points, ancestor markers and inaccessible metadata; assert no mutations.
- [x] Implement IExplorerCommand with fast `E_PENDING` state negotiation, hidden invalid sources, and revalidation on Invoke.
- [x] Start adjacent GameTier.exe with `--promote` and safely quoted absolute path; never use shell string evaluation or elevation.
- [x] Export COM factory/unload entry points; provide CTest and package manifest for the signed modern-menu identity path.
- [x] Build and test with MSVC when available; CI must exercise the native build.

### Task 3: Application startup and Settings

Files: `shared/src@jvm/ui/PromotionLaunch.kt`, mirrored tests, `desktopApp/src/main.kt`, `shared/src@jvm/ui/SettingsScreen.kt`, and registration service under `settings`.

- [x] Test strict `--promote <absolute-path>` parsing, Unicode/spaces, malformed arguments and existing administrator-restart compatibility.
- [x] Route the parsed selection through AppController.select after startup; do not auto-confirm.
- [x] Add integration state and a Settings toggle backed by actual registration, with visible errors and no effect on promotion UAC handling.
- [x] Test registration commands and error handling at the native process boundary, using isolated registry locations for integration tests.

### Task 4: Installer lifecycle and verification

Files: `.github/workflows/ci.yml`, `.github/workflows/release.yml`, installer resources/scripts beside the native project, concise developer instructions.

- [x] Build native command in CI and release; include it beside the packaged launcher.
- [x] Register per-user integration on install and remove it on uninstall. Respect a disabled preference through upgrades.
- [x] Ship the reviewed unsigned classic-menu implementation as requested. Signed identity helpers remain separate until release signing is configured and validated.
- [x] Build, test and stage the JVM app; run 32 real native DLL tests and build a JDK 25 MSI with database assertions. MSVC/CTest and clean-user install/upgrade/uninstall are configured in CI; local lifecycle testing refuses the existing GameTier installation.
- [x] Review the full diff and report precise verified behavior and remaining external prerequisites.

### Review corrections

- [x] Exclude `.gametier` backup directories and all descendants in Explorer, review and execution, including case variants and lexical/canonical aliases.
- [x] Preserve lexical source paths through repeated reviews, removal and UAC restart; canonicalize operational filenames after checking eligibility, including Windows 8.3 aliases.
- [x] Serialize transfers and inventory across processes using the original user's application-data lock; verify cancellation with contended locks on Windows.
- [x] Coordinate DataStore versions, writes and notifications across processes; preserve existing failure and write-through behavior.
- [x] Complete fresh concurrency review.
