# Explorer command

The native `GameTierExplorer.dll` opens promotion, demotion, or source-backup
recovery review in the adjacent `GameTier.exe`. Build the x64 DLL with CMake
3.24+, MSVC and the Windows SDK:

```powershell
cmake -S desktopApp/packaging/explorer -B build/explorer -A x64
cmake --build build/explorer --config Release
ctest --test-dir build/explorer -C Release --output-on-failure
```

CTest loads the real DLL through its COM factory and uses isolated per-process
registry preferences and temporary filesystem fixtures. Its adjacent `GameTier.exe`
is an argument recorder; do not ship that test executable. The DLL statically
links the MSVC runtime. Managed-game tests require permission to create symbolic
links (run the test process elevated if necessary). Temporary DOS drive mappings
keep canonical source-backup fixtures inside disposable directories.

Metadata parsing uses the vendored MIT-licensed
[nlohmann/json 3.12.0](https://github.com/nlohmann/json/releases/tag/v3.12.0)
in `third_party/nlohmann`; builds do not download dependencies.

The MSIX manifest registers CLSID `{9B6ED281-384A-4DBF-98C0-D26B6D41B352}` as an
apartment-threaded COM surrogate and assigns file and directory context-menu
verbs to it. The per-user `Software\GameTier` DWORD
`ExplorerIntegrationEnabled` defaults to enabled when absent.

Conveyor supplies the signed package identity, icons, and application launcher.
Its installer trusts the self-signed certificate with administrator approval.
The package declarations are in [conveyor.conf](../installer/conveyor.conf).
The standalone sparse-identity scripts and MSI builder remain legacy tooling;
the release workflow no longer uses them.

The identity and COM declarations follow Microsoft's
[Explorer integration documentation](https://learn.microsoft.com/en-us/windows/apps/desktop/modernize/integrate-packaged-app-with-file-explorer).
