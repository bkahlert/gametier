# Promotion inventory

Approved in conversation: use one hidden `.gametier.json` per destination,
containing origins for all promotions there. Promotions occupy direct children.
No source-root configuration or broad filesystem search is required.

The filesystem determines whether indexed relationships are currently valid.
Unindexed children are unrecognized; contents of indexed directories belong to
their promotion. Interrupted operations remain visible as needing attention.
Record transfer intent before moving data. Preserve unusable indexes and report
missing metadata; never silently repair, adopt, delete, or overwrite game data.
Remember used destinations independently of the current default, and report
inaccessible destinations as unavailable. Index initialization is explicit on
first use; a remembered destination with a lost index cannot be silently reset.

The existing blocking transfer primitives remain independently usable. A new
game module coordinates persisted intent, transfers, completion, and inventory.
The UI will use this module in a subsequent change.
