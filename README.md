<p align="center">
  <img src="shared/composeResources/drawable/gametier.svg" alt="GameTier Boostpad icon" height="192">
</p>

# GameTier [![CI](https://github.com/bkahlert/gametier/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/bkahlert/gametier/actions/workflows/ci.yml) [![Buy Me A Coffee](https://img.shields.io/static/v1?label=&message=%E2%98%95%20Buy%20Me%20A%20Coffee&color=FFDD00)](https://www.buymeacoffee.com/bkahlert)

Move games to faster storage while keeping their original locations working.
GameTier moves whole game folders or individual files, such as ROMs, and leaves
a symbolic link behind so your launcher or emulator can still find them.
Move them back whenever you need the space.

Promotion keeps a snapshot on the original drive, so it does not free space
there. The promoted files receive subsequent changes; the snapshot stays as it
was at promotion. Snapshots live in
`<source-drive>:\.gametier\backups\<original-name>`.

![GameTier listing two folder-based games and a ROM-based game](docs/screenshots/promotions.png)

## Install

Requires Windows x64 (Windows 10 version 2004 or later).

### Trust the certificate (once per PC)

GameTier uses a self-signed signing certificate. Install it before installing the app:

1. Download [gametier.crt](https://github.com/bkahlert/gametier/releases/latest/download/gametier.crt).
2. Open it, choose **Install Certificate**, select **Local Machine**, and approve
   the administrator prompt.
3. Select **Place all certificates in the following store**, browse to
   **Trusted People**, and finish the wizard.

### Install the app

1. Download and run [gametier.exe](https://github.com/bkahlert/gametier/releases/latest/download/gametier.exe).
2. Wait for installation to finish (the download may take a few minutes), then
   launch **GameTier** from the Start menu.

Updates install automatically in the background when GameTier is closed.

## Use

1. Close the game and any other programs writing to its files.
2. Drag files or folders into GameTier, or use **Add promotion** to choose them.
3. Choose a folder on your faster drive, review the selection, and click **Promote**.
   Your first destination becomes the default for future additions.

You can also right-click one file or folder and choose **Promote with GameTier**.
On Windows 11, it appears directly in the main context menu. If the entry is
missing after installation, restart **Windows Explorer** from Task Manager
(or sign out and back in). Turn this option off in **Settings**.
Right-click a managed game link, its promoted files, or its backup folder to
choose **Demote with GameTier**. Selecting an item inside returns the whole game.
When only the snapshot remains, the menu offers **Restore backup with GameTier**.

![Reviewing promotion of a game to an SSD drive](docs/screenshots/review.png)

Use **Demote** to return the latest game files to their original location. With a
source backup, the original drive needs temporary space for the returning files;
the backup and promoted copy are removed after a successful return.

If the promoted files become unavailable, **Restore backup** returns the snapshot.
Changes made after promotion will be missing. **Details** shows the backup location;
ordinary files and restore instructions also allow manual recovery without GameTier.
Older promotions without snapshots are marked **Unprotected** and can still be demoted.

Search finds promotions by name or path; **Activity** shows queued work and results.
Keep GameTier open until transfers finish.

If Windows blocks symbolic links, open **Activity → Allow symbolic links… →
Restart as administrator**. Approve the Windows prompt, then review and confirm
the pending moves. For other failures, inspect the details in Activity before retrying.

## Development

Built with Kotlin and Compose Multiplatform. See the [development guide](desktopApp/README.md)
to build, run, or test from source.

## License

[MIT](LICENSE)

Packaged with [Conveyor](https://hydraulic.dev/).
