# Package Ownership Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this approved plan inline. Steps use checkbox syntax for tracking.

**Goal:** Make source and test ownership discoverable through responsibility packages.

**Architecture:** Preserve both build modules and all source-set roles. Mirror
`game`, `files`, `settings`, and `ui` packages in tests; separate reusable
filesystem fixtures from script workflow support.

**Tech Stack:** Kotlin Multiplatform, Kotlin Toolchain, JUnit Jupiter, Kotest.

**Spec:** `docs/superpowers/specs/2026-11-06-package-ownership-design.md`

## Global constraints

- Preserve behavior, existing test cases, fixture interfaces, and JUnit tags.
- Keep `com.bkahlert.gametier.MainKt` and generated resource names unchanged.
- Preserve transfers' independence from settings/UI and filesystem reuse.
- Keep `scripts/TestVolumes.psm1` and the existing Kotlin filesystem matrix.
- Work on `refactor/package-ownership`; do not commit to `main`.

## Task 1: Establish and preserve the baseline

- [x] Run `.\kotlin.bat test -m shared` and save JUnit test identities/results.
- [x] Save source contents for comparison after removing package/import lines.
- [x] Inventory all 39 handwritten Kotlin files and assign their owners using
  the spec. Keep the desktop launcher at its existing path.

## Task 2: Move tests and reusable support

- [x] Move each test and feature fixture to its subject's package directory.
- [x] Move `FilesystemFixtures.kt`, `SymlinkFixtures.kt`, `TestVolumes.kt`,
  `FilesystemFixturesTest.kt`, and `SymlinkFixturesTest.kt` under
  `shared/test@jvm/testsupport/filesystem/`.
- [x] Extract `projectRoot`, `runTestPowerShell`, and `isWindowsElevated` into
  `shared/test@jvm/testsupport/workflow/WorkflowFixtures.kt`; move
  `FilesystemTestsTest`, `ElevatedSymlinkTestsTest`, `VolumeFixturesTest`, and
  `TestVolumesTest` to the same workflow package.
- [x] Extract `assertLinkFailure` from `SymlinkCreatorTest.kt` into
  `shared/test@jvm/files/SymlinkAssertions.kt`, keeping its body unchanged.
- [x] Update test packages and explicit imports for their new dependencies.

## Task 3: Move production sources and update navigation

- [x] Move `Promoter.kt`, `Demoter.kt`, and `GameTransfer.kt` into `game/`;
  move `FileMover.kt` and `SymlinkCreator.kt` into `files/` under `src@jvm`.
- [x] Move settings sources into `settings/` under their existing roots.
- [x] Move the UI/greeting/platform sources into `ui/` under existing roots.
- [x] Update production package declarations, cross-package imports, and the
  desktop launcher's `App` import; preserve all operation bodies.
- [x] Update AGENTS.md's volume-fixture path and record concise ownership
  guidance. Add a short linked responsibility map to README.md.

## Task 4: Verify and review

- [x] Compare original and moved Kotlin bodies, including extracted fixtures.
- [x] Run `.\kotlin.bat build` and `.\kotlin.bat test`.
- [x] Compare final JUnit test identities/results with the baseline, allowing
  only the approved package-name changes; account for prerequisite aborts.
- [x] Check stale active paths, accidental flat sources, and `git diff --check`.
- [x] Request an independent code review and resolve actionable findings.
- [x] Report the final layout, validation, and unmet integration prerequisites.
