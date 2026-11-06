# Explorer command

The native `GameTierExplorer.dll` opens promotion review in the adjacent
`GameTier.exe`. Build the x64 DLL with CMake 3.24+, MSVC and the Windows SDK:

```powershell
cmake -S desktopApp/packaging/explorer -B build/explorer -A x64
cmake --build build/explorer --config Release
ctest --test-dir build/explorer -C Release --output-on-failure
```

CTest loads the real DLL through its COM factory and uses isolated per-process
registry preferences and temporary filesystem fixtures. Its adjacent `GameTier.exe`
is an argument recorder; do not ship that test executable. The DLL statically
links the MSVC runtime.

The installer registers CLSID `{9B6ED281-384A-4DBF-98C0-D26B6D41B352}` as an
apartment-threaded in-process server and assigns it to `ExplorerCommandHandler`
for `*` and `Directory`. The per-user `Software\GameTier` DWORD
`ExplorerIntegrationEnabled` defaults to enabled when absent.

Windows 11's primary menu additionally requires the signed sparse identity
package described by `AppxManifest.xml.in`. Release packaging must supply its
certificate-matching publisher, four-part package version and the three PNG assets,
then sign and register it with the install directory as its external location.
An unsigned manifest alone does not enable the primary menu. Do not install
certificates or change Developer Mode to bypass release signing.

The identity and COM declarations follow Microsoft's
[Explorer integration documentation](https://learn.microsoft.com/en-us/windows/apps/desktop/modernize/integrate-packaged-app-with-file-explorer).
