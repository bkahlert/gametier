# Explorer promotion

The Explorer action opens the existing promotion review for one selected file or
directory. It never moves data or requests elevation while constructing a menu.
The default destination and the existing first-destination chooser remain in use.

Source eligibility excludes filesystem roots, missing or special entries, links,
the `.gametier` backup directory and its entire subtree, and any selection whose
directory or ancestor contains `.gametier.json`.
The marker's presence is sufficient, regardless of its contents or whether the
destination is remembered. An inspection failure hides the command. The same
managed-directory boundary applies to application review and transfer execution.
Symlink privilege is not part of eligibility: an otherwise eligible selection
remains eligible when promotion will require the existing UAC restart flow.

Explorer performs only bounded source-side checks and does not walk descendants,
load the JVM, inspect destination settings, or create probe links. Expensive
checks happen in the application and eligibility is checked again on invocation.
Multiple selections have no command. Integration is on by default and can be
disabled in Settings. Uninstall removes registration.

The Windows integration consists of an isolated native IExplorerCommand DLL,
a `--promote <absolute-path>` application entry point, and installer registration.
The modern Windows 11 menu additionally requires package identity. The existing
unsigned per-user MSI cannot silently install a trusted signed identity package;
release signing is an explicit distribution prerequisite, currently being
clarified with the user. Do not change certificate trust or Developer Mode.

Tests cover managed boundaries, inspection failures, selection types, no symlink
preflight during eligibility, startup argument validation, review dispatch,
settings changes and registration lifecycle. Native menu tests exercise the real
command and filesystem. Packaging and Explorer checks must be reported separately
from JVM tests, including any unavailable build prerequisites.
