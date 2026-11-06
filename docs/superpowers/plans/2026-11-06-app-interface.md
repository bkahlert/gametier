# App Interface Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development or
> superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Deliver the approved Windows Compose interface with reviewed additions,
sequential transfers, session results, settings, and safe window lifetime.

**Architecture:** A JVM application controller owns observable immutable state and
an application-lifetime coroutine scope. Read-only selection preparation and a
manager-backed operation adapter isolate filesystem work from Compose. Desktop
selection and drag/drop translate native selections into review requests.

**Tech Stack:** Existing Kotlin Toolchain, Compose Material 3, coroutines,
Preferences DataStore, Kotlin/JUnit nested tests, Kotest, coroutine test scheduler.

**Spec:** ../specs/2026-11-06-app-interface-design.md

## Global constraints

- Keep the existing Compose application and GameTier icon.
- Route transfers through PromotionManager; do not change transfer mechanics.
- No overwriting, merging, automatic repair, cancellation, percentages or ETA.
- Settings validation accepts native absolute paths without requiring storage.
- Registration is a mutation and is never part of selection review.
- Keep the last inventory visible during work; serialize refresh with transfers.
- Failures retain phase, paths, causes, and recovery details for inspection.
- Keep source responsibility under ui, game, files, settings and mirror tests.

## Shared contracts

All new types below use package `com.bkahlert.gametier.ui` in `shared/src@jvm/ui`.
Paths are `java.nio.file.Path`; inventory types are the existing game types.

```kotlin
enum class ReviewStatus { READY, SKIPPED, BLOCKED }
data class ReviewItem(val original: Path, val destination: Path?,
    val status: ReviewStatus, val message: String)
data class SelectionReview(val directory: Path?, val items: List<ReviewItem>,
    val adjustments: List<String> = emptyList(), val problem: String? = null)
fun interface SelectionPreparer {
    suspend fun prepare(paths: List<Path>, directory: Path?,
        inventory: List<DestinationInventory>, remembered: Set<String>,
        reserved: List<Pair<Path, Path>>): SelectionReview
}
class FileSelectionPreparer : SelectionPreparer
```

`reserved` pairs are original and complete target paths for queued/running work.
The controller owns AppState, TransferRequest, ActivityItem and AppController;
the renderer reads their definitions before implementation. Production controller
constructor: `AppController(scope: CoroutineScope, settings: AppSettings,
operations: PromotionOperations, preparer: SelectionPreparer)`.
`PromotionOperations` supplies suspend `inventory`, `promote`, `demote`, and
`destinationProblem(Path): String?`; `ManagerPromotionOperations` wraps the manager.

## Task 1: Read-only selection review

Files: create `shared/src@jvm/ui/SelectionReview.kt`, `FileSelectionPreparer.kt`;
tests `shared/test@jvm/ui/FileSelectionPreparerTest.kt` and local fixtures as needed.

- [x] Write failing tests using temporary filesystem fixtures for deduplication,
  parent/child collapse, parent aliases and case identity, preserving selected
  links, already indexed originals/data, missing originals, unsupported entries,
  nested destinations, occupied targets, same-name selections, reserved targets,
  unset destination, and available/fresh/lost/invalid/unavailable destinations.
  Example outcome: preparing `[folder, folder/child, folder]` produces one READY
  item and adjustment messages, without creating `.gametier.json`.
- [x] Run `.\kotlin.bat test -m shared` and establish the missing behavior.
- [x] Resolve parent aliases without dereferencing a selected link; use ordinary
  entries' real identities where safe, then collapse selected descendants.
  Read destination metadata without registration. Fresh unremembered missing
  indexes are allowed; remembered missing indexes and invalid/unavailable folders
  block. Use existing source validation for known blockers, preserving error text.
- [x] Test review leaves all data/index/settings unchanged, including failures.
- [x] Run shared tests; review and commit `feat(ui): prepare promotion selections`.

## Task 2: Application state, settings, queue and lifetime

Files: create `shared/src@jvm/ui/AppState.kt`, `AppController.kt`,
`PromotionOperations.kt`; tests `shared/test@jvm/ui/AppControllerTest.kt`,
`PromotionOperationsTest.kt`, and `AppFixtures.kt`.

- [x] Write coroutine tests with deterministic gates. Assert that confirming two
  items starts only the first; completing it starts the second. Change the
  default while blocked and assert the second retains its captured destination.
  Confirm additions while busy and assert order and exactly-once execution.
- [x] Cover review generations (stale async results discarded), first-use default
  persistence retaining pending selections, save validation/storage/read failures,
  preserved typed input, skipped/blocked filtering, duplicate confirmations,
  navigation, search, removal, canceled reviews and unavailable inventory.
- [x] Cover demotion confirmation, result retention, successful removal, isolated
  failure continuation, destination failure stopping affected work, and refresh
  failures preserving the last snapshot. No exception-message pattern matching.
- [x] Cover idle close, keep open, close after draining, failure while draining,
  and close during preparation/settings persistence so app work is not canceled.
- [x] Run shared tests to establish failing behavior, then implement state reducer
  methods and one worker. All UI mutations occur on the supplied scope dispatcher;
  filesystem preparation and destination health run on IO. Propagate coroutine
  cancellation rather than reporting it as a user transfer failure.
- [x] Inspect destination health after a failure: inaccessible/unwritable storage,
  missing remembered metadata or invalid metadata stops pending work using that
  physical destination. Inspection failure is conservative for that destination;
  independent destinations proceed. Show stopped items as explicit session results.
- [x] Keep settings errors next to the input and transfer errors until acknowledged.
  Never register before confirmation; manager.promote performs safe first use.
- [x] Run shared tests; review and commit `feat(ui): orchestrate session transfers`.

## Task 3: Compose screens

Files: replace `shared/src/ui/App.kt` with JVM UI entry under
`shared/src@jvm/ui/App.kt`; create `PromotionScreen.kt`, `SettingsScreen.kt`,
`ReviewDialogs.kt`, `ActivityPanel.kt`, `UiComponents.kt` as needed.

- [x] Read the controller's concrete state/actions and approved screen study.
  Add runnable presentation/state tests for search by name/original/destination,
  needs-attention actions, empty vs unavailable notices, operation overlays,
  readiness counts and failure acknowledgment. Use existing test stack.
- [x] Render Promotions as home, title actions, default below heading, search,
  scrollable rows, paths available through selection/tooltips/details, status icons
  plus words, Demote/Details. Show first-use guidance without hiding errors.
- [x] Render Settings with editable path, Browse, explicit Save, inline error and
  read-only remembered folders. Render review with destination override, removals,
  adjustments/readiness, cancellation and one Promote confirmation.
- [x] Render demotion, details, busy-close dialogs and collapsible bounded activity
  panel; keep list and toolbar usable with activity expanded at small sizes.
- [x] Follow platform light/dark appearance and neutral/purple palette. Use real
  Compose modal focus and labeled native controls; no prototype navigation tabs.
- [x] Compile and run shared tests; commit `feat(ui): render promotion workflows`.

## Task 4: Desktop adapters and window

Files: modify `desktopApp/src/main.kt`; create native picker/drop adapters under
`shared/src@jvm/ui` or `desktopApp/src/ui` with tests at the matching source root.

- [x] Test picker cancellation/selection/error translation and dropped file URI
  decoding without moving data. Native API adapters accept a dialog boundary so
  deterministic tests cover routing and cancellation without showing windows.
- [x] Use Windows shell file/folder dialogs via JDK desktop facilities, owned by
  the application window. Keep picker work separate from operation scope.
- [x] Add content-wide Compose drag-and-drop with hover overlay, accepting files
  and folders and routing to selection review. Handle malformed/native errors.
- [x] Create controller once at application lifetime, collect its exit state,
  route close to requestClose, and dispose only after work is drained. Preserve
  application icon and set useful initial/minimum dimensions.
- [x] Build and test; commit `feat(ui): connect desktop selection and lifetime`.

## Task 5: Verification and delivery

- [x] Run `.\kotlin.bat test -m shared` and `.\kotlin.bat build`.
- [ ] Launch `.\kotlin.bat run -m desktopApp`; verify normal home, first-use/drop
  review, picker cancellation, keyboard/modal focus, Settings Save, resizing,
  platform appearance, activity and close behavior. Use only disposable fixtures
  for any transfer. Record unavailable native checks explicitly.
- [ ] Run filesystem workflow when integration requires real symlink success;
  preserve and report prerequisites instead of claiming skipped tests passed.
- [x] Review whole diff against spec and resolve actionable findings with tests.
- [x] Update README core UI description and spec implementation status.
- [ ] Commit remaining logical changes, push feature branch and create review PR.
  Do not merge without the user's instruction.

## Execution record

- Baseline: 117 successful, 45 prerequisite-aborted, zero failed shared tests.
- Branch: `feat/app-interface`, based on approved design commit `80383a1`.
- Independent selection and controller implementations may proceed concurrently
  against the shared contract; renderer starts after controller contract exists.
- Tasks 1–4 implemented with deterministic controller/presentation tests and
  read-only filesystem preparation tests. No transfer mechanics were replaced.
- Final full shared suite: 266 tests, 219 successful, 47 prerequisite-aborted,
  zero failed. The aborts require the opt-in volume matrix or Windows symlink
  permission. The filesystem workflow was not run for this UI change.
- Final desktop build passed. Existing FileMover nullable-view warnings and the
  JDK native-access warning were observed; no new dependency was introduced.
- Launched the real application; observed platform dark appearance, normal
  first-use home, existing icon, and successful idle close.
- Native picker interaction/cancellation, actual Explorer file and folder drops,
  keyboard/modal focus, resizing, light appearance, and busy-close interaction
  remain manual checks. Computer Use retained its Escape-stop state despite
  user-authorized retries and a fresh control session. Picker translation, drop
  COPY negotiation, and close-drain behavior have runnable boundary/state tests.
- Review fixes cover initial inventory trust, post-transfer reservations across
  refresh failure, cross-path conflicts, collision removal, settings-read recovery,
  retained review overrides, alias reconnection, and unwritable metadata.
- Close when finished drains the queue but leaves failures open for inspection.
- Final scoped review resolved all findings, including retaining attempted paths
  through destination-level inspection failures until physical storage is available.
