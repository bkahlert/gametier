[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $Installer,
    [Parameter(Mandatory)][string] $ExplorerLibrary
)

$ErrorActionPreference = 'Stop'

function Read-Rows($Database, [string] $Query, [int] $ColumnCount) {
    $view = $Database.OpenView($Query)
    try {
        $view.Execute() | Out-Null
        $rows = @()
        while ($record = $view.Fetch()) {
            $values = @(for ($column = 1; $column -le $ColumnCount; $column++) {
                $record.GetType().InvokeMember('StringData', 'GetProperty', $null, $record, @($column))
            })
            $rows += ,$values
        }
        return ,$rows
    } finally { $view.Close() | Out-Null }
}

function Assert-Count($Rows, [int] $Count, [string] $Description) {
    if ($Rows.Count -ne $Count) { throw "$Description expected $Count rows, got $($Rows.Count)." }
}

$windowsInstaller = New-Object -ComObject WindowsInstaller.Installer
try {
    $database = $windowsInstaller.GetType().InvokeMember('OpenDatabase', 'InvokeMethod', $null, $windowsInstaller,
        @((Resolve-Path -LiteralPath $Installer).Path, 0))
    $files = Read-Rows $database 'SELECT `FileName`, `FileSize`, `Component_` FROM `File`' 3
    $notice = @($files | Where-Object { ($_[0] -split '\|')[-1] -ceq 'GameTierExplorer.LICENSE.txt' })
    Assert-Count $notice 1 'Explorer dependency license payload'
    $noticeSource = Join-Path $PSScriptRoot '../explorer/third_party/nlohmann/LICENSE.MIT'
    if ([long]$notice[0][1] -ne (Get-Item -LiteralPath $noticeSource).Length) { throw 'Explorer dependency license differs from the vendored license.' }
    $library = @($files | Where-Object { ($_[0] -split '\|')[-1] -ceq 'GameTierExplorer.dll' })
    $launcher = @($files | Where-Object { ($_[0] -split '\|')[-1] -ceq 'GameTier.exe' })
    Assert-Count $library 1 'Explorer DLL payload'
    Assert-Count $launcher 1 'Application launcher payload'
    if ([long]$library[0][1] -ne (Get-Item -LiteralPath $ExplorerLibrary).Length) { throw 'Explorer DLL payload size differs from the native build.' }
    $components = Read-Rows $database 'SELECT `Component`, `Directory_` FROM `Component`' 2
    $libraryDirectory = @($components | Where-Object { $_[0] -ceq $library[0][2] })[0][1]
    $launcherDirectory = @($components | Where-Object { $_[0] -ceq $launcher[0][2] })[0][1]
    if ($libraryDirectory -cne $launcherDirectory) { throw 'Explorer DLL must be installed next to GameTier.exe.' }

    $registrations = Read-Rows $database 'SELECT `Root`, `Key`, `Name`, `Value`, `Component_` FROM `Registry`' 5
    $expected = @(
        @('Software\Classes\CLSID\{9B6ED281-384A-4DBF-98C0-D26B6D41B352}\InprocServer32', '', '[INSTALLDIR]GameTierExplorer.dll', 'GameTierExplorerCom'),
        @('Software\Classes\CLSID\{9B6ED281-384A-4DBF-98C0-D26B6D41B352}\InprocServer32', 'ThreadingModel', 'Apartment', 'GameTierExplorerCom')
    )
    foreach ($kind in @(@('*', 'GameTierExplorerFile'), @('Directory', 'GameTierExplorerDirectory'))) {
        $path = "Software\Classes\$($kind[0])\shell\GameTier.Promote"
        $expected += ,@($path, '', 'Promote with GameTier', $kind[1])
        $expected += ,@($path, 'ExplorerCommandHandler', '{9B6ED281-384A-4DBF-98C0-D26B6D41B352}', $kind[1])
        $expected += ,@($path, 'MultiSelectModel', 'Single', $kind[1])
    }
    foreach ($registration in $expected) {
        $matching = @($registrations | Where-Object {
            $_[0] -ceq '1' -and $_[1] -ceq $registration[0] -and $_[2] -ceq $registration[1] -and
            $_[3] -ceq $registration[2] -and $_[4] -ceq $registration[3]
        })
        Assert-Count $matching 1 "Per-user registration $($registration[0])/$($registration[1])"
    }
    Assert-Count @($registrations | Where-Object { $_[2] -ceq 'ExplorerIntegrationEnabled' }) 0 'Installer preference writes'
    $features = Read-Rows $database 'SELECT `Feature_`, `Component_` FROM `FeatureComponents`' 2
    foreach ($component in @('GameTierExplorerCom', 'GameTierExplorerFile', 'GameTierExplorerDirectory')) {
        Assert-Count @($features | Where-Object { $_[0] -ceq 'DefaultFeature' -and $_[1] -ceq $component }) 1 "Default feature $component"
    }
    foreach ($path in @('Software\Classes\CLSID\{9B6ED281-384A-4DBF-98C0-D26B6D41B352}', 'Software\Classes\*\shell\GameTier.Promote', 'Software\Classes\Directory\shell\GameTier.Promote')) {
        Assert-Count @($registrations | Where-Object { $_[0] -ceq '1' -and $_[1] -ceq $path -and $_[2] -ceq '-' }) 1 "Uninstall registration removal $path"
    }
    $restartManagerShutdown = Read-Rows $database "SELECT ``Value`` FROM ``Property`` WHERE ``Property`` = 'MSIRMSHUTDOWN'" 1
    Assert-Count $restartManagerShutdown 1 'Upgrade force shutdown policy'
    if ($restartManagerShutdown[0][0] -cne '1') { throw "Upgrade force shutdown policy expected '1', got '$($restartManagerShutdown[0][0])'." }
    Write-Output 'Explorer MSI package passed: payload, per-user registration, feature inclusion, uninstall ownership, upgrade shutdown policy.'
} finally { [Runtime.InteropServices.Marshal]::FinalReleaseComObject($windowsInstaller) | Out-Null }
