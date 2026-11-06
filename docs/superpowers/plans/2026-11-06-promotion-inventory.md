# Promotion Inventory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Discover promotions and unmatched destination content without source-folder configuration.

**Architecture:** Persist a versioned destination-local JSON index. A game-level coordinator serializes intent/transfer/completion and supplies filesystem-validated snapshots. Settings remember destination directories independently of the default.

**Tech Stack:** Kotlin/JVM, kotlinx.serialization JSON tree model, existing DataStore, coroutines, JUnit and Kotest.

**Spec:** `docs/superpowers/specs/2026-11-06-promotion-inventory-design.md`

## Global Constraints

- Keep existing transfer primitives independently usable.
- Use existing filesystem fixtures and NTFS/ReFS matrix.
- No UI changes, automatic cleanup, adoption, or repair.
- Preserve damaged metadata and partial game data.

### Task 1: Remember destinations

**Files:** `shared/src/settings/AppSettings.kt`, `shared/src/settings/DataStoreAppSettings.kt`, matching settings tests.

**Interfaces:** Add `val promotionFolders: Flow<Set<String>>` and `suspend fun rememberPromotionFolder(path: String)` to `AppSettings`. History contains explicitly registered destinations only. Default changes leave history unchanged; inventory includes the current default separately. This distinguishes a fresh default from a registered destination whose index was lost. Validate stored and supplied values using existing folder validation.

- [x] Write tests for retention across replacement/clear/reopen, remembering without changing default, duplicate suppression, invalid values/types, failed writes, and legacy defaults.
  ```kotlin
  settings.rememberPromotionFolder(first)
  settings.rememberPromotionFolder(second)
  settings.clearDefaultPromotionFolder()
  val result = settings.promotionFolders.first()
  result shouldBe setOf(first, second)
  ```
- [x] Run `.\kotlin.bat test -m shared --include-classes=*DataStoreAppSettingsTest*` and observe the missing behavior.
- [x] Implement the interface and serialized DataStore edits; run matching unit/integration tests.

### Task 2: Destination index and inventory

**Files:** New `shared/src@jvm/game/PromotionIndex.kt`, `PromotionInventory.kt`, matching tests; JSON dependency in module/catalog.

**Interfaces:** Internal index records carry direct-child destination, absolute origin and state (`PROMOTING`, `PROMOTED`, `DEMOTING`). Public snapshots expose directory availability, promotion health and unmatched direct children. Reject unsupported versions, malformed records, duplicate destinations/origins, unsafe names and linked metadata. Writes force a temporary file then replace atomically; set Windows hidden attribute before replacement; retain prior index on failure.

- [x] Write index tests for round trips, strict validation, missing/corrupt preservation, hidden attributes and replacement failures. Write inventory tests for committed/pending/broken records, unmatched files/directories, unavailable roots, missing/corrupt metadata and symlink redirection.
  ```kotlin
  val result = PromotionInventory.inspect(folder)
  result.status shouldBe DestinationStatus.MISSING_INDEX
  ```
- [x] Run targeted tests to establish red; implement index and inventory; rerun to green.

### Task 3: Coordinate transfers

**Files:** New `shared/src@jvm/game/PromotionManager.kt`, matching unit/integration tests and fixtures; README core-feature description.

**Interfaces:** `PromotionManager(settings: AppSettings = getAppSettings())` exposes suspending `registerDirectory(directory: Path)`, `inventory(): List<DestinationInventory>`, `promote(original: Path, directory: Path)`, `demote(directory: Path, name: String)`. Perform blocking work on Dispatchers.IO and serialize in-process operations. Register first-use destinations explicitly, preserve lost remembered indexes, persist pending intent before calling existing primitives, retain pending intent on errors, commit promotion/remove demotion only after success. Validate indexed original→target before demoting.

- [x] Write tests for registration, persisted ordering before transfer, failed writes preventing movement, retained failed/pending transfers, finalization failures, collisions, successful round trips, concurrency and remembered offline roots.
  ```kotlin
  manager.registerDirectory(folder)
  manager.promote(original, folder)
  val result = manager.inventory()
  result.single().promotions.single().condition shouldBe PromotionCondition.PROMOTED
  ```
- [x] Observe red, implement coordinator, verify targeted tests, then run `.\kotlin.bat test -m shared` and `.\kotlin.bat build`.
- [x] Add matrix tests using `testVolumeCases` and `testVolumePairCases`; run existing opt-in workflow where available, reporting environment limitations precisely.
- [x] Review whole diff for spec compliance and error coverage before completion.

## Verification

- Final ordinary suite: 117 successful, 45 prerequisite-aborted, 0 failures.
- Full project build: successful.
- Full Windows NTFS/ReFS verification completed after rerunning with UAC consent:
  143 unelevated and 107 elevated tests passed, with no failures or aborted tests.
  This includes same-volume and cross-volume indexed file/directory round trips,
  failure recovery, concurrency, and index finalization failures. Disposable
  volumes and temporary fixture directories were cleaned up.
- The JVM's DataStore Unsafe warning makes the outer PowerShell log-redirection
  command report an error status despite successful tests and cleanup. A minimal
  native-command probe confirmed this shell behavior independently.
- Independent review completed; findings addressed with regression tests for
  missing-index demotion, remembered demotion destinations, duplicate JSON
  properties, and long metadata strings.
