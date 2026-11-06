[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $Installer,
    [Parameter(Mandatory)][string] $UpgradeInstaller,
    [Parameter(Mandatory)][string] $ExplorerLibrary
)

$ErrorActionPreference = 'Stop'
$classId = '{9B6ED281-384A-4DBF-98C0-D26B6D41B352}'
$registrationKeys = @(
    "Software\Classes\CLSID\$classId",
    'Software\Classes\*\shell\GameTier.Promote',
    'Software\Classes\Directory\shell\GameTier.Promote'
)
$preferenceKey = 'Software\GameTier'
$preferenceName = 'ExplorerIntegrationEnabled'

function Assert-Equal($Actual, $Expected, [string] $Description) {
    if ($Actual -cne $Expected) { throw "$Description expected '$Expected', got '$Actual'." }
}

function Invoke-Installer([string[]] $Arguments, [string] $Log) {
    $process = Start-Process msiexec.exe -ArgumentList ($Arguments + @('/qn', '/norestart', '/l*v', "`"$Log`"")) -Wait -PassThru -WindowStyle Hidden
    if ($process.ExitCode -notin @(0, 3010)) {
        throw "Windows Installer failed with exit code $($process.ExitCode). See $Log."
    }
}

function Assert-Registration([string] $InstallDirectory) {
    $library = Join-Path $InstallDirectory 'GameTierExplorer.dll'
    Assert-Equal (Test-Path -LiteralPath (Join-Path $InstallDirectory 'GameTier.exe')) $true 'Installed launcher'
    Assert-Equal (Test-Path -LiteralPath $library) $true 'Adjacent Explorer DLL'
    Assert-Equal (Get-FileHash -LiteralPath $library).Hash (Get-FileHash -LiteralPath $ExplorerLibrary).Hash 'Installed Explorer DLL content'
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey("$($registrationKeys[0])\InprocServer32")
    try {
        if ($null -eq $key) { throw 'COM registration is missing.' }
        Assert-Equal $key.GetValue('') $library 'COM DLL path'
        Assert-Equal $key.GetValue('ThreadingModel') 'Apartment' 'COM threading model'
    } finally { if ($null -ne $key) { $key.Dispose() } }
    foreach ($path in $registrationKeys[1..2]) {
        $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey($path)
        try {
            if ($null -eq $key) { throw "Explorer registration is missing: $path" }
            Assert-Equal $key.GetValue('') 'Promote with GameTier' 'Explorer title'
            Assert-Equal $key.GetValue('ExplorerCommandHandler') $classId 'Explorer command'
            Assert-Equal $key.GetValue('MultiSelectModel') 'Single' 'Explorer selection model'
        } finally { if ($null -ne $key) { $key.Dispose() } }
    }
}

foreach ($file in @($Installer, $UpgradeInstaller, $ExplorerLibrary)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required installer fixture is missing: $file" }
}
$windowsInstaller = New-Object -ComObject WindowsInstaller.Installer
try {
    $products = $windowsInstaller.GetType().InvokeMember('RelatedProducts', [Reflection.BindingFlags]::GetProperty,
        $null, $windowsInstaller, @('{9db43bc2-4812-4649-a1a0-66489bdf19c0}'))
    if ($products.Count -ne 0) { throw 'Installer lifecycle tests require a user without an existing GameTier installation.' }
} finally { [Runtime.InteropServices.Marshal]::FinalReleaseComObject($windowsInstaller) | Out-Null }
foreach ($path in $registrationKeys) {
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey($path)
    if ($null -ne $key) {
        $key.Dispose()
        throw 'Installer lifecycle tests require a user without existing GameTier Explorer registration.'
    }
}
$key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey($preferenceKey)
try {
    if ($null -ne $key -and $key.GetValueNames() -contains $preferenceName) {
        throw 'Installer lifecycle tests require a user without an existing Explorer preference.'
    }
} finally { if ($null -ne $key) { $key.Dispose() } }

$fixture = Join-Path ([IO.Path]::GetTempPath()) "gametier-installer-$([guid]::NewGuid().ToString('N'))"
$installDirectory = Join-Path $fixture 'GameTier'
New-Item -ItemType Directory -Path $fixture | Out-Null
$installed = $null
try {
    Invoke-Installer @('/i', "`"$((Resolve-Path -LiteralPath $Installer).Path)`"", "INSTALLDIR=`"$installDirectory`"") (Join-Path $fixture 'install.log')
    $installed = $Installer
    Assert-Registration $installDirectory
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey($preferenceKey)
    try {
        if ($null -ne $key) { Assert-Equal ($key.GetValueNames() -contains $preferenceName) $false 'Default preference remains absent' }
    } finally { if ($null -ne $key) { $key.Dispose() } }

    $key = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey($preferenceKey)
    try { $key.SetValue($preferenceName, 0, [Microsoft.Win32.RegistryValueKind]::DWord) } finally { $key.Dispose() }
    Invoke-Installer @('/i', "`"$((Resolve-Path -LiteralPath $UpgradeInstaller).Path)`"", "INSTALLDIR=`"$installDirectory`"") (Join-Path $fixture 'upgrade.log')
    $installed = $UpgradeInstaller
    Assert-Registration $installDirectory
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey($preferenceKey)
    try {
        Assert-Equal $key.GetValue($preferenceName) 0 'Disabled preference after upgrade'
        Assert-Equal $key.GetValueKind($preferenceName) ([Microsoft.Win32.RegistryValueKind]::DWord) 'Preference type after upgrade'
    } finally { $key.Dispose() }

    Invoke-Installer @('/x', "`"$((Resolve-Path -LiteralPath $installed).Path)`"") (Join-Path $fixture 'uninstall.log')
    $installed = $null
    foreach ($path in $registrationKeys) {
        $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey($path)
        if ($null -ne $key) { $key.Dispose(); throw "Uninstall retained Explorer registration: $path" }
    }
    Assert-Equal (Test-Path -LiteralPath (Join-Path $installDirectory 'GameTierExplorer.dll')) $false 'Uninstalled Explorer DLL'
    Write-Output 'Explorer installer lifecycle passed: install, disabled upgrade, uninstall.'
} finally {
    if ($null -ne $installed) {
        Invoke-Installer @('/x', "`"$((Resolve-Path -LiteralPath $installed).Path)`"") (Join-Path $fixture 'cleanup.log')
    }
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey($preferenceKey, $true)
    try { if ($null -ne $key) { $key.DeleteValue($preferenceName, $false) } } finally { if ($null -ne $key) { $key.Dispose() } }
    Write-Output "Installer lifecycle logs: $fixture"
}
