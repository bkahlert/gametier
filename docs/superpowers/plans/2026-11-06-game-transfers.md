# Game Transfers Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Relocate games with an original-location symlink and safely reverse the operation.

**Architecture:** Public Promoter and Demoter objects compose FileMover and SymlinkCreator. Internal path validation and filesystem callbacks keep recovery local and allow deterministic failure tests.

**Tech Stack:** Kotlin/JVM, NIO, JUnit Jupiter, Kotest, Kotlin Toolchain.

**Spec:** `docs/superpowers/specs/2026-11-06-game-transfers-design.md`

## Global constraints

- Support regular files and directories; do not overwrite or merge.
- Preserve ambiguous partial copies; recovery is best effort.
- Reuse the existing NTFS/ReFS fixtures and test stack.
- No settings/UI integration or new dependencies.

## Task 1: Promotion

Files: create `shared/src@jvm/Promoter.kt`, `GameTransfer.kt`, and `shared/test@jvm/PromoterTest.kt`; extend internal validation in `FileMover.kt`.

Interface: `Promoter.promote(original: Path, destination: Path): Unit`.

- [x] Add failing tests for relocation, original-path access, validation, preflight failure/cleanup, partial move preservation, rollback, and failed rollback.
- [x] Run `.\kotlin.bat test -m shared`; confirm the new interface is missing.
- [x] Reuse `FileMover.validateSource(path)` to scan without moving. Normalize parents, reject occupied/recursive/incompatible destinations, then preflight a unique sibling link.
- [x] Implement move followed by final link creation; recover only after a normally returned move and while the original path is free.
- [x] Run the shared tests, asserting contents and path states as well as cause/suppressed errors.

Example contract assertion:

```kotlin
val result = Promoter.promote(original, destination)
result shouldBe Unit
Files.isSymbolicLink(original) shouldBe true
Files.readString(original) shouldBe "game data"
Files.readString(destination) shouldBe "game data"
```

## Task 2: Demotion

Files: create `shared/src@jvm/Demoter.kt`, `shared/test@jvm/DemoterTest.kt`; share extracted test fixtures in `GameTransferFixtures.kt`.

Interface: `Demoter.demote(original: Path): Unit`; consumes internal source validation and filesystem callbacks from Task 1.

- [x] Add failing tests for files/trees, relative targets, unsupported links/contents, failed unlink, partial moves, link recovery, and failed recovery.
- [x] Run the shared tests and verify missing demotion behavior.
- [x] Validate and resolve the direct target before removing the original link; move target back and restore access conservatively after failure.
- [x] Run the shared suite and inspect all failures.

Example contract assertion:

```kotlin
val result = Demoter.demote(original)
result shouldBe Unit
Files.isSymbolicLink(original) shouldBe false
Files.readString(original) shouldBe "game data"
Files.exists(target, NOFOLLOW_LINKS) shouldBe false
```

## Task 3: Real filesystem verification and delivery

Files: create `PromoterIntegrationTest.kt` and `DemoterIntegrationTest.kt` under `shared/test@jvm`; update the core filesystem overview in `README.md`.

- [x] Add file/tree tests using `testVolumePairCases` and `testVolumeCases`, tagged `symlink-success`.
- [x] Add real locked-file failure cases for promotion and demotion across volumes.
- [x] Run `.\kotlin.bat test`, `.\kotlin.bat build`, and `scripts/Test-Filesystems.ps1`.
- [x] Review the diff against the spec and fix any uncovered behavior with a failing test first.
- [x] Record results and limitations; leave work on its feature branch without merging main.

## Verification

Full project tests and build passed. The NTFS/ReFS runner passed both phases, including all 92 elevated symlink tests. Code review findings on aliases and cross-volume rollback were resolved.
