# Source Backups Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development for scoped tasks and review.

**Goal:** Retain independently restorable source-drive snapshots for promotions.

**Architecture:** Reuse the verified filesystem transfer engine for nondestructive
copies. A game-layer backup store owns source records and backup lifecycle;
PromotionManager coordinates it with the existing destination index. The UI
exposes protection and explicit recovery without automatically restoring files.

**Tech Stack:** Kotlin/JVM, NIO, kotlinx.serialization, Compose, existing JUnit/Kotest stack.

**Spec:** `docs/superpowers/specs/2026-11-06-source-backups-design.md`

## Global Constraints

- Flat unchanged names beneath `<drive>:\.gametier\backups`; never overwrite.
- Existing destination index remains readable; ordinary files enable manual recovery.
- No automatic expiry, no source data loss on failed operations.
- Migration trusts supplied contents; preserves active promoted data.
- Tests use isolated backup locations, never real drive-root backups.

### Task 1: Verified copies

Files: `shared/src@jvm/files/FileMover.kt`, `shared/test@jvm/files/FileMoverTest.kt`.

- [x] Add failing tests for `FileMover.copy(source, destination, onProgress)`:
  source retained, independent data, directory/attribute fidelity, source change,
  progress observer failure, existing destination and unsupported links.
- [x] Run `kotlin.bat test -m shared`; confirm new tests fail for missing copy API.
- [x] Extract common verified transfer with copy retaining source on every volume.
- [x] Re-run tests; preserve existing move semantics and Windows native copy.

### Task 2: Source backup lifecycle

Files: new `shared/src@jvm/game/BackupStore.kt`, game backup transfer/store tests;
`PromotionManager.kt`, `PromotionInventory.kt`, game manager fixtures/tests.
Consumes: `FileMover.copy(Path, Path, (TransferProgress) -> Unit)`.
Produces: `PromotionManager.restoreBackup(original: Path, onProgress: (TransferProgress) -> Unit = {})`;
`Promotion.backup: Path?`, `Promotion.canRestoreBackup: Boolean`.

- [x] Test flat collision rejection, file/tree snapshots, metadata/manual recovery,
  failure preservation, demotion of changed data, cleanup, missing-target recovery,
  refusal of occupants/redirected links/live targets, and legacy unprotected data.
- [x] Add injectable backup-root resolution for isolated tests.
- [x] Persist source intent before modifying paths; copy/verify before switching.
- [x] Discover valid source backups independently from destination metadata.
- [x] Add trusted-copy adoption API for migration with identity validation but no hashes.
- [x] Run shared tests, including source-root and destination-index failures.

### Task 3: Recovery interface

Files: `shared/src@jvm/ui` operations/controller/state/presentation/dialog/screen;
mirrored UI tests; `README.md`.
Consumes: Task 2 manager restore API and promotion backup fields.

- [x] Test recovery confirmation, serialized execution/progress, stale-state refusal,
  failure reporting, recoverable/legacy presentation, and manager adapter.
- [x] Offer Restore backup only for eligible missing targets; show backup location.
- [x] Update user copy to describe retained source space and demotion staging space.
- [x] Run `kotlin.bat test`, then `kotlin.bat build`.

### Task 4: Review and supplied-copy migration

- [x] Review the complete diff for loss/overwrite risks and fix findings with tests.
- [x] Inspect supplied copy names and existing promotion metadata without hashing.
- [x] If all supplied copies are finished and matched, adopt them through the tested
  migration API. Otherwise leave unmatched/in-progress copies and report precisely.
- [x] Re-run required checks and report actual outcomes and migration status.
