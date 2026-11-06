# Application settings

Approved in the settings design interview on 2026-11-06.

Provide a common Kotlin `AppSettings` API backed by AndroidX Preferences
DataStore on JVM. Expose an observable nullable `defaultPromotionFolder` and
suspending set and clear operations. Keep storage keys and DataStore types
internal. A shared, lazily initialized production store lives under
`%LOCALAPPDATA%/GameTier/settings.preferences_pb` for the current OS user.

The preference is an explicitly selected destination parent directory. It starts
unset. Accept native absolute paths, including unavailable folders, and retain
their original text. Reject blank, relative, and malformed paths. Storage and
invalid persisted-value errors remain visible; do not reset corrupt data.
Writes return after persistence. Support concurrent callers in one process.

This change supplies the building block only. Settings UI, promotion integration,
additional platform backends, and simultaneous processes are outside its scope.
Executable tests define the detailed behavior and failure cases.
