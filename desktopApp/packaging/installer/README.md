# Installer packaging

`Build-Installer.ps1` uses the installed JDK's WiX template, adds per-user Explorer
registration to its default feature, and includes the native DLL beside the
launcher. It verifies the resulting MSI database against that installation
contract. It never writes the Settings preference, so upgrades preserve disabled
integration. Native build instructions are [beside the DLL](../explorer/README.md).

Settings live in `%LOCALAPPDATA%\GameTierData`, outside the installation tree.
The embedded native action copies legacy settings from `%LOCALAPPDATA%\GameTier`
before `RemoveExistingProducts`: older jpackage installers recursively delete
that directory. Each upgrade attempt atomically refreshes a staging file; the
app publishes it under the settings lock on first launch. Existing current
settings take precedence. Copy errors abort the upgrade before removal.

`Test-ExplorerInstaller.ps1` accepts two MSI versions and the native DLL. On a
clean user profile it installs, disables integration, upgrades, checks the
preference, default promotion folder, folder history and registration, then
uninstalls and checks settings retention. It refuses existing GameTier
installations, registrations or preferences. Logs remain in the printed temporary
directory. CI runs this lifecycle; tests on an existing developer installation
can still use the read-only `Test-ExplorerInstallerPackage.ps1` MSI checks.

## Signed Windows 11 identity

Unsigned releases currently enable the classic context menu. The separate
identity scripts prepare the Windows 11 primary-menu distribution path; release
automation does not activate it. A trusted release certificate matching the
manifest publisher and Windows SDK `makeappx.exe`/`signtool.exe` are prerequisites.
The scripts do not install certificates or change Developer Mode.
The preparation and registration commands follow Microsoft's
[identity package guide](https://learn.microsoft.com/en-us/windows/apps/desktop/modernize/grant-identity-to-nonpackaged-apps).

To inspect generated manifest and icon assets without signing:

```powershell
.\desktopApp\packaging\installer\Build-ExplorerIdentity.ps1 `
  -PackageVersion 0.1.1.0 -Publisher 'CN=Your release publisher' -StageOnly
```

Use a new destination to build the signed package with `-CertificateThumbprint`
and `-TimestampUrl`, using a certificate already available in the current user's
`My` certificate store. The builder signs and verifies trust before reporting the
package. From Windows PowerShell, `Set-ExplorerIdentity.ps1 -Action Register`
requires `-Publisher`, `-Package` and `-InstallDirectory`; `-Action Remove`
requires the same publisher and removes only that identity. Register only after
the MSI has installed the application and the signed package's `Assets` directory
has been deployed beside `GameTier.exe`. Registration verifies those installed
assets against the signed package. Remove the identity before uninstall.
These commands still require end-to-end validation with the actual trusted release
certificate before enabling them in installer/release automation.
