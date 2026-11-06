# Source backups

Promotion retains a promotion-time snapshot on the original drive. Backups are
ordinary files at `<drive>:\.gametier\backups\<original-name>\data`, beside
`backup.json` and `RESTORE.txt`. Names are flat, unchanged, and have no suffix.
An existing backup blocks a new promotion. Source-side metadata is sufficient
for recovery without settings or the destination disk. Backups never expire.

Copy and verify the target before relocating the original into its backup and
replacing the original path with a symbolic link. Failures preserve recoverable
data and never overwrite a new occupant. Keep the existing target index
compatible; existing promotions without backups are visibly unprotected.

Demote returns the latest promoted files through source-side temporary storage,
then removes the target copy and snapshot after the return succeeds. The
snapshot remains safe during the return; insufficient space stops the operation.
Restore backup is an explicit action for a missing target. It moves the snapshot
back to the original location, removing only the expected dangling link, never
an ordinary occupant or redirected link. Successful restoration consumes the
backup. Interrupted transfers remain visible and retain recovery material.

The application exposes backup status, its location, and recovery for a missing
target, including when destination metadata is unavailable. Recovery remains
possible manually from the ordinary files and instructions.

Later migrate the user's three supplied copies from `E:\.gametier` into the
new layout, matching their existing promotion origins. Trust them as complete
original snapshots without content comparisons. Never modify active promoted
data. Do not adopt a copy still being written or guess at an unmatched copy.
