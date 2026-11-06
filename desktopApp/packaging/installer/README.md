# Installer packaging

`conveyor.conf` packages the staged JVM application and native Explorer DLL into
a self-signed Windows EXE/MSIX distribution. Updates use the stable GitHub
`releases/latest/download` URL. Background updates wait for running applications
to close. The release workflow uploads the complete site to a draft release
before marking it latest.

`Build-UpdateSite.ps1` accepts the version, staged JARs, Explorer DLL, and a fresh
destination. It creates a version-specific configuration and builds
`<destination>/site`. `-StageOnly` prepares the configuration for packaging on a
Linux worker. Conveyor bundles Java 25 and signs with its free generated key.
Use [Conveyor 22.3](https://conveyor.hydraulic.dev/22.3.1/download-conveyor/);
Windows builds need permission to create symbolic links.

## Release key

Generate one root key with `conveyor keys generate --no-passphrase --no-keyring`.
Back up the `app.signing-key` value from Conveyor's `defaults.conf` outside the
repository. Set the GitHub Actions secret `CONVEYOR_SIGNING_KEY` to that value.
Keep the same key for all releases: existing installations trust its certificate.
No Conveyor account or purchased certificate is required.

Local builds use Conveyor's existing key. The release workflow requires the
secret explicitly and fails if it is absent. Pull-request CI generates a
separate disposable key, never the release key. Never publish CI test packages.
`-TestPackage` permits remote identity differences as warnings for these fixtures;
release packages retain Conveyor's strict update-site consistency checks.

## Verification

`Test-UpdateSite.ps1` inspects the actual signed MSIX, launcher, Explorer
declarations, license files, settings exclusions, and App Installer update policy.
It changes neither installed packages nor certificate trust.

`Test-UpdateInstaller.ps1` installs two local packages on an elevated, disposable
Windows user profile. It checks symlinks and persistent settings, defers an update
while a packaged process is running, and uninstalls. It refuses existing GameTier
installations or user data. CI runs this lifecycle in Windows PowerShell 5.1.
After the deferral assertions, CI ends only its own disposable GUI process if it
does not close promptly. Normal window closure is checked manually with the
isolated portable build.

Settings stay in `%LOCALAPPDATA%\GameTierData`; both the JVM launcher and MSIX
configuration preserve that external path. The Explorer preference remains in
`HKCU\Software\GameTier`, shared with the native command and retained on uninstall.

## Existing MSI installation

The sole existing installation is switched manually: close GameTier, back up
`%LOCALAPPDATA%\GameTierData`, uninstall the MSI, then run the Conveyor EXE.
Legacy `%LOCALAPPDATA%\GameTier` preferences should also be backed up before
uninstalling an older MSI. There is no automatic MSI-to-MSIX migration.

`Build-Installer.ps1`, its MSI checks, and the separate sparse-identity scripts
remain available for legacy packages; they are not part of release automation.
