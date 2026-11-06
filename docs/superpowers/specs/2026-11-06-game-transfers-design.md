# Promotion and demotion

Approved through the `/grill-me` discussion on 2026-11-06.

`Promoter.promote(original: Path, destination: Path): Unit` relocates a regular
file or directory to the complete destination path and leaves an absolute
symbolic link at its original location. `Demoter.demote(original: Path): Unit`
resolves the original symbolic link and returns its target to that location.
Both operations are blocking JVM operations, independent of settings and UI.

Reuse FileMover and SymlinkCreator. Accept relative paths and parent aliases,
same-volume and cross-volume transfers. Require existing parents; reject roots,
occupied destinations, recursive placement, unsupported contents, and repeated
requests. Demotion accepts external symbolic links and relative stored targets,
but rejects dangling links, chains, junctions, and special entries. Validate the
entire target before removing a demotion link.

Promotion creates and removes a uniquely named sibling symlink before moving.
Preflight failure aborts without moving; removal failure reports the leftover
link. Final link creation still needs recovery. A successful move followed by
link failure triggers one move-back attempt only if the original entry is free.
Move failures preserve every surviving entry, including partial copies.
Demotion removes only the link, then moves its target. A failed move may restore
the link only while its location is free and its target is valid. Ambiguous
copies remain untouched. Recovery is best effort, never an overwrite or merge.

Failures are contextual IOExceptions with phase, original path, destination or
target when known, and original cause. Attach recovery failures as suppressed
exceptions; report any leftover preflight link. No automatic elevation,
persistent crash recovery, progress, or cooperative cancellation. Callers must
serialize transfers of a game and prevent concurrent writes.

Keep the public interfaces small. Reuse source validation internally and provide
an internal filesystem-operations seam for deterministic fault injection.
Exercise real filesystem operations in the NTFS/ReFS matrix using existing
fixtures; cover validation, preflight, move, link, and recovery failures with
minimal fixtures and no mocked subject.
