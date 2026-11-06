# GameTier app interface

Status: approved in the design interview on 2026-11-06, including the screen
study. Implemented on `feat/app-interface`; see the
[implementation plan and verification record](../plans/2026-11-06-app-interface.md).

[Open the interactive screen study](../../designs/app-interface/index.html).
The study uses sample data and illustrative transitions. It does not move
files or exercise the application services. This specification describes
the agreed product; the study supplies its visual reference. Its sample
logic is not a production implementation. Light and dark are review
controls outside the application window, not additional settings.

## Direction

A Windows-first, list-first desktop utility for a personal collection and
small batches. Keep the existing Compose application and GameTier icon.
Use neutral surfaces, familiar controls, restrained purple accents, and
clear text alongside status icons. Retain Promote and Demote, explaining
what each operation does where the user starts it.

The promotions list is the home screen. Its toolbar contains Add promotion
and Settings. Display the default destination immediately below the heading.
Each row shows its name, original location, promotion folder, condition,
and a Demote or Details action. Full paths remain accessible when truncated.
Search helps find an item by name or path. Avoid a permanent navigation
sidebar for these two destinations.

Use the platform appearance, readable contrast, keyboard navigation,
visible focus, and text labels alongside status colors. Keep the list
scrollable when activity expands and keep the primary actions accessible
at smaller desktop window sizes. Preserve the existing application icon.

## Screen reference

The study's screen tabs are review navigation, not application navigation.

| Screen | Purpose |
| --- | --- |
| Promotions | Searchable home list, destination summary, Add, Settings, and row actions |
| Drag & drop | Content-wide drop highlight leading to review, without starting a move |
| Add review | Items, readiness, selection adjustments, destination override, and one Promote confirmation |
| Activity | Sequential work, indeterminate activity, and session results |
| Settings | Default folder and previously used destinations |
| First use | Normal home with setup guidance that retains the pending selection |
| Needs attention | Destination notices and inspectable promotion problems |
| Demote | One confirmation showing where data will move back |
| Close while busy | Keep open or finish queued work before exiting |

## Adding promotions

The content area accepts dropped files and folders, with a clear overlay
during the drag. Add promotion also offers file and folder pickers. Both
paths lead to a compact review of the items and their destination before
the user starts the move.

Use the default folder automatically and allow an override for this
addition. Keep that choice separate from the saved default. The review
shows selection adjustments, already promoted items, and known blockers.
Remove duplicate selections. Represent a selected directory and its
selected children as one promotion. Clearly distinguish items that will
run from skipped or blocked items, including occupied destination names.
Do not silently overwrite, merge, or rename data to make an addition work.
Keep transfer-time failures visible because review cannot guarantee a
successful filesystem operation.

On first use, show the ordinary home screen with an invitation to add an
item and choose a destination. Retain items dropped before setup. The first
chosen folder becomes the default.

## Transfers and results

Confirmed work enters a sequential queue. Users can add more work while a
transfer runs; each confirmed item retains its destination. A collapsible
activity panel shows queued work, active operations, and session results.
Use indeterminate activity indicators and explicit states rather than
percentages or estimated times unsupported by the current services.

Continue independent items after an isolated failure. Stop affected work
when a shared destination problem prevents progress. Keep failures visible
until acknowledged; details explain the problem and relevant paths.
Persistent operation history, pause, active cancellation, and automatic
repair are outside this first release.

Closing during work offers Keep open or Close when finished. The latter
finishes queued work before exiting. Inventory refresh must respect the
manager's existing serialization with transfers.

## Demotion and attention states

Demote opens one compact confirmation showing where the data will move
back. Show operation activity, remove a successfully demoted item from the
promotions list, and retain its result in session activity.

Unavailable destinations remain visible as destination-level notices.
Interrupted or inconsistent promotions remain visible with a Needs
attention condition and inspectable details. Distinguish missing storage
from an empty collection. Offer explanations grounded in the actual error;
do not present a general repair or retry operation that the backend cannot
safely support.

## Settings

A dedicated Settings view contains the default promotion folder and a
read-only list of previously used folders. Changing the default leaves
existing promotions and confirmed queued work in place. Use Browse and an
editable path with explicit Save for typed changes, as shown in the
approved study. Display validation and persistence errors next to the
setting and preserve the pending input on failure.

## Existing foundations

The repository uses Kotlin Multiplatform targeting JVM, Compose with
Material 3, coroutines, and AndroidX Preferences DataStore. Extend these
foundations rather than introducing a web application or replacing the
transfer implementation.

| Location | Current responsibility |
| --- | --- |
| [App.kt](../../../shared/src@jvm/ui/App.kt) | Compose screens, dialogs, and application state rendering |
| [main.kt](../../../desktopApp/src/main.kt) | Desktop window, native selection, and transfer-aware lifetime |
| [PromotionManager.kt](../../../shared/src@jvm/game/PromotionManager.kt) | Suspending `promote(original, directory)`, `demote(directory, name)`, `registerDirectory(directory)`, and `inventory()` |
| [PromotionInventory.kt](../../../shared/src@jvm/game/PromotionInventory.kt) | Destination status, indexed promotion condition, paths, and unrecognized children |
| [AppSettings.kt](../../../shared/src/settings/AppSettings.kt) | Observable nullable default folder and independently remembered destinations |
| [shared/module.yaml](../../../shared/module.yaml) | Compose, lifecycle, coroutine, persistence, and test dependencies |
| [gametier.svg](../../../shared/composeResources/drawable/gametier.svg) | Existing brand and application icon |

Keep screen state and operation orchestration under `ui`; use JVM or
desktop boundaries for native file/folder selection, drag-and-drop paths,
and window lifetime. Keep filesystem mechanics under `files`, promotion
semantics under `game`, and preference persistence under `settings`.
Route transfers through `PromotionManager` so index and remembered-folder
updates remain coordinated. Own active work at application lifetime so
navigation and recomposition cannot cancel or duplicate a transfer.

Read the existing [transfer](2026-11-06-game-transfers-design.md),
[inventory](2026-11-06-promotion-inventory-design.md), and
[settings](2026-11-06-settings-design.md) specs and their tests before
planning integration. They remain authoritative for the existing services.

## Integration constraints

- The manager serializes all calls, including inventory, across the
  process. Keep the last inventory visible during work, overlay live
  operation state, and refresh after operations. Persisted `PROMOTING`
  and `DEMOTING` records denote interrupted work during inspection; they
  are not a live progress feed.
- There is no batch controller, public dry-run API, percentage progress,
  capacity estimate, or safe cancellation interface. Queue orchestration,
  selection preparation, desktop integration, and view state are new work.
  Registration can write an index and settings, so it is not a dry run.
- Fresh default folders and previously used folders with lost metadata
  can both report `MISSING_INDEX`. First-use registration must not become
  a repair action that resets a remembered folder's missing index. Keep
  `INVALID_INDEX` and `UNAVAILABLE` problems visible as well.
- A failed operation may already have moved data, left partial copies,
  or failed only when saving final metadata. Present the reported phase
  and paths; do not infer rollback or offer a generic automatic retry.
  Failure classification for queue continuation belongs in the
  implementation plan; the current API primarily exposes IOExceptions.
- Settings accept native absolute paths even when a drive is unavailable.
  Actual promotion requires an existing destination directory. Reuse the
  settings validator rather than the mockup's illustrative JavaScript
  path check. Surface permission failures without automatic elevation. For a
  failed link preflight before data moves, offer an explicit **Allow symbolic
  links…** action. **Restart as administrator** requests Windows UAC consent,
  retains the original user's settings, and reopens that selection for review.
  Cancellation leaves the current window open. This is not a general retry for
  transfers that may already have moved data.
- Real selection handling must account for Windows path identity and
  aliases without following a selected promotion's link into its data.
  Existing transfer validation remains authoritative for unsupported
  entries, reparse points, nested destinations, and collisions.
- Unrecognized destination children are not promotions. Keep their
  diagnostic information available without presenting them as demotable
  records or automatically adopting them.

## Scope boundary

The release includes these screens, native selection/drop integration,
session queue/results, settings wiring, and transfer-aware window lifetime.
It does not add automatic repair or adoption, rename/merge-on-conflict,
space estimates, transfer speed, ETA, pause, active cancellation, persistent
queue/history, tray operation, destination forgetting, or extra preferences.

## Implementation handoff reference

The following handoff guided implementation. Current verification results and
remaining native checks are recorded in the linked implementation plan.

1. Read [AGENTS.md](../../../AGENTS.md), this spec, the linked screen study,
   and existing service tests. Preserve this approved design; clarify only
   material contradictions discovered during implementation planning.
2. Check the working tree and branch. The design artifacts were prepared
   on `docs/app-interface-design`. Keep implementation on its own feature
   branch, carrying this spec and reference with it. Integrate via a PR as
   required by the project instructions.
3. Use the writing-plans workflow to create
   `docs/superpowers/plans/2026-11-06-app-interface.md`. Plan the UI state
   and queue, native selection/drop adapters, Compose screens, and window
   lifetime as testable units. Choose concrete seams for preparation and
   failure classification after inspecting current APIs; this document
   is the product spec, not an unreviewed code-level implementation plan.
4. Express observable behavior and error paths as runnable tests in the
   implementation changes. Reuse Kotlin/JUnit nested contexts, Kotest
   assertions, and coroutine test facilities. Mirror source packages in
   tests; use minimal deterministic fixtures and the existing NTFS/ReFS
   support for filesystem integration. Cover queue ordering, destination
   capture, selection preparation, settings failures, attention states,
   and close-during-work behavior at their lowest effective levels.
5. Validate the actual desktop integration, including native picker
   cancellation, files and directory drops, keyboard/modal focus,
   appearance, resizing, and close handling. The browser study cannot
   establish native behavior. Consult the README for test prerequisites.

Typical Windows validation commands for the implementation:

```powershell
.\kotlin.bat test -m shared
.\kotlin.bat build
.\kotlin.bat run -m desktopApp
```

Use the opt-in filesystem workflow when integration coverage requires it:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Filesystems.ps1
```

The user visually reviewed and approved the HTML study. Its embedded
JavaScript passed a syntax check. Automated browser rendering was not
completed because the preview tool could not locate Chrome; no application
runtime tests have been run for this documentation-only handoff. The
prototype uses fixed sample states, simulated picker/results behavior, and
incomplete workflow logic, including error acknowledgment and first-use
drop routing. Implement from this spec and test the real behavior.
