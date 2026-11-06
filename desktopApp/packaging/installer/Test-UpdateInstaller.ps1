[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $Package,
    [Parameter(Mandatory)][string] $UpgradePackage,
    [Parameter(Mandatory)][string] $Certificate
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSEdition -cne 'Desktop' -or -not [Environment]::Is64BitProcess) {
    throw 'Run lifecycle tests in 64-bit Windows PowerShell 5.1 on a disposable Windows CI user profile.'
}
$principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Lifecycle tests require an elevated disposable CI user to trust the test certificate and exercise symlinks.'
}
foreach ($command in @('Add-AppxPackage', 'Get-AppxPackage', 'Get-AppxPackageManifest', 'Remove-AppxPackage', 'Invoke-CommandInDesktopPackage')) {
    Get-Command $command -ErrorAction Stop | Out-Null
}
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Assert-Equal($Actual, $Expected, [string] $Description) {
    if ($Actual -cne $Expected) { throw "$Description expected '$Expected', got '$Actual'." }
}

function Read-PackageIdentity([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Required lifecycle fixture is missing: $Path" }
    $archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Path).Path)
    try {
        $entries = @($archive.Entries | Where-Object FullName -ieq 'AppxManifest.xml')
        if ($entries.Count -ne 1) { throw "Expected one AppxManifest.xml in $Path." }
        $reader = [IO.StreamReader]::new($entries[0].Open())
        try { [xml] $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $ns = [Xml.XmlNamespaceManager]::new($manifest.NameTable)
        $ns.AddNamespace('f', 'http://schemas.microsoft.com/appx/manifest/foundation/windows10')
        $identity = $manifest.SelectSingleNode('/f:Package/f:Identity', $ns)
        if ($null -eq $identity) { throw "MSIX identity is missing in $Path." }
        return [pscustomobject]@{
            Name = $identity.GetAttribute('Name')
            Publisher = $identity.GetAttribute('Publisher')
            Version = [version]$identity.GetAttribute('Version')
            Architecture = $identity.GetAttribute('ProcessorArchitecture')
        }
    } finally { $archive.Dispose() }
}

function Assert-CleanProfile {
    $packages = @(Get-AppxPackage -AllUsers | Where-Object { $_.Name -like 'GameTier*' -or $_.PackageFamilyName -like 'GameTier*' })
    if ($packages.Count -ne 0) { throw 'Lifecycle tests refuse an existing GameTier Appx installation for any user.' }
    foreach ($directory in @($legacySettingsDirectory, $settingsDirectory)) {
        if (Test-Path -LiteralPath $directory) { throw "Lifecycle tests require absent user data: $directory" }
    }
    foreach ($hive in @([Microsoft.Win32.Registry]::CurrentUser, [Microsoft.Win32.Registry]::LocalMachine)) {
        foreach ($path in @($registrationKeys + 'Software\GameTier')) {
            $key = $hive.OpenSubKey($path)
            if ($null -ne $key) { $key.Dispose(); throw "Lifecycle tests refuse existing registry data: $hive\$path" }
        }
        foreach ($uninstallRoot in @('Software\Microsoft\Windows\CurrentVersion\Uninstall', 'Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall')) {
            $root = $hive.OpenSubKey($uninstallRoot)
            try {
                if ($null -ne $root) {
                    foreach ($name in $root.GetSubKeyNames()) {
                        $key = $root.OpenSubKey($name)
                        try {
                            if ($null -ne $key -and [string]$key.GetValue('DisplayName') -like '*GameTier*') {
                                throw "Lifecycle tests refuse an existing GameTier uninstall entry: $hive\$uninstallRoot\$name"
                            }
                        } finally { if ($null -ne $key) { $key.Dispose() } }
                    }
                }
            } finally { if ($null -ne $root) { $root.Dispose() } }
        }
    }
    $windowsInstaller = New-Object -ComObject WindowsInstaller.Installer
    try {
        $products = $windowsInstaller.GetType().InvokeMember('RelatedProducts', [Reflection.BindingFlags]::GetProperty,
            $null, $windowsInstaller, @('{9db43bc2-4812-4649-a1a0-66489bdf19c0}'))
        try {
            $count = $products.GetType().InvokeMember('Count', [Reflection.BindingFlags]::GetProperty, $null, $products, $null)
            if ($count -ne 0) { throw 'Lifecycle tests refuse an existing GameTier MSI installation.' }
        } finally { [Runtime.InteropServices.Marshal]::FinalReleaseComObject($products) | Out-Null }
    } finally { [Runtime.InteropServices.Marshal]::FinalReleaseComObject($windowsInstaller) | Out-Null }
}

function Get-OwnedPackage {
    $packages = @(Get-AppxPackage -Name $initial.Name | Where-Object { $_.Publisher -ceq $initial.Publisher })
    if ($packages.Count -ne 1) { throw "Expected one test-owned package, got $($packages.Count)." }
    return $packages[0]
}

function Assert-Installed($ExpectedIdentity) {
    $installed = Get-OwnedPackage
    Assert-Equal $installed.Name $ExpectedIdentity.Name 'Installed identity'
    Assert-Equal $installed.Publisher $ExpectedIdentity.Publisher 'Installed publisher'
    Assert-Equal ([version]$installed.Version) $ExpectedIdentity.Version 'Installed version'
    Assert-Equal $installed.Status.ToString() 'Ok' 'Installed package status'
    $installedManifest = Get-AppxPackageManifest -Package $installed.PackageFullName
    $ns = [Xml.XmlNamespaceManager]::new($installedManifest.NameTable)
    $ns.AddNamespace('f', 'http://schemas.microsoft.com/appx/manifest/foundation/windows10')
    $ns.AddNamespace('com', 'http://schemas.microsoft.com/appx/manifest/com/windows10')
    $ns.AddNamespace('d4', 'http://schemas.microsoft.com/appx/manifest/desktop/windows10/4')
    $ns.AddNamespace('d5', 'http://schemas.microsoft.com/appx/manifest/desktop/windows10/5')
    $classes = @($installedManifest.SelectNodes('//com:Class', $ns) | Where-Object { $_.GetAttribute('Id').Trim('{}') -ieq $classId.Trim('{}') })
    Assert-Equal $classes.Count 1 'Registered packaged COM class count'
    Assert-Equal $classes[0].GetAttribute('Path') 'bin/GameTierExplorer.dll' 'Registered COM DLL'
    foreach ($kind in @('*', 'Directory')) {
        $verbs = @($installedManifest.SelectNodes('//d4:FileExplorerContextMenus/d5:ItemType', $ns) |
            Where-Object { $_.GetAttribute('Type') -ceq $kind } | ForEach-Object { $_.SelectNodes('d5:Verb', $ns) } |
            Where-Object { $_.GetAttribute('Clsid').Trim('{}') -ieq $classId.Trim('{}') })
        Assert-Equal $verbs.Count 1 "Registered Explorer $kind verb count"
    }
    foreach ($name in @('bin/GameTier.exe', 'bin/GameTierExplorer.dll', 'GameTierExplorer.LICENSE.txt')) {
        $file = Join-Path $installed.InstallLocation $name
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Installed asset is missing: $file" }
    }
    $application = $installedManifest.SelectSingleNode('/f:Package/f:Applications/f:Application', $ns)
    if ($null -eq $application) { throw 'Installed application registration is missing.' }
    return [pscustomobject]@{ Package = $installed; AppId = $application.GetAttribute('Id') }
}

function Assert-RetainedSettings([string] $Description) {
    Assert-Equal (Get-FileHash -LiteralPath $settingsFile).Hash $settingsHash "$Description settings content"
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey($preferenceKey)
    try {
        if ($null -eq $key) { throw "$Description lost the Explorer preference." }
        Assert-Equal $key.GetValue($preferenceName) 0 "$Description disabled Explorer preference"
        Assert-Equal $key.GetValueKind($preferenceName) ([Microsoft.Win32.RegistryValueKind]::DWord) "$Description Explorer preference type"
    } finally { if ($null -ne $key) { $key.Dispose() } }
}

function Start-PackageFixture($Registration, [string] $Phase, [bool] $Initialize) {
    $eventPrefix = "Local\gametier-$([guid]::NewGuid().ToString('N'))"
    $ready = [Threading.EventWaitHandle]::new($false, [Threading.EventResetMode]::ManualReset, "$eventPrefix-ready")
    $release = [Threading.EventWaitHandle]::new($false, [Threading.EventResetMode]::ManualReset, "$eventPrefix-release")
    $done = [Threading.EventWaitHandle]::new($false, [Threading.EventResetMode]::ManualReset, "$eventPrefix-done")
    $resultFile = Join-Path $fixture "$Phase-result.json"
    $arguments = '-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $fixtureScript +
        '" -EventPrefix "' + $eventPrefix + '" -Result "' + $resultFile + '" -Settings "' + $settingsFile +
        '" -SettingsFixture "' + $settingsFixture + '" -LinkRoot "' + (Join-Path $fixture "$Phase-link") +
        '" -Launcher "' + (Join-Path $Registration.Package.InstallLocation 'bin/GameTier.exe') +
        '" -Initialize ' + ([int]$Initialize)
    try {
        Invoke-CommandInDesktopPackage -PackageFamilyName $Registration.Package.PackageFamilyName -AppId $Registration.AppId `
            -Command "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -Args $arguments -PreventBreakaway
        if (-not $ready.WaitOne([TimeSpan]::FromSeconds(60))) { throw "Packaged $Phase fixture did not become ready within 60 seconds; see $fixture." }
        $result = Get-Content -Raw -LiteralPath $resultFile | ConvertFrom-Json
        if (-not $result.Passed) { throw "Packaged $Phase fixture failed: $($result.Error)" }
        Assert-Equal $result.PackageFullName $Registration.Package.PackageFullName "Packaged $Phase process identity"
        $process = Get-Process -Id $result.ProcessId -ErrorAction Stop
        return [pscustomobject]@{ Ready = $ready; Release = $release; Done = $done; Process = $process; Result = $resultFile;
            ApplicationProcessId = [int]$result.ApplicationProcessId }
    } catch {
        $release.Set() | Out-Null
        $ready.Dispose(); $release.Dispose(); $done.Dispose()
        throw
    }
}

function Stop-PackageFixture($Running) {
    if ($null -eq $Running) { return }
    try {
        $Running.Release.Set() | Out-Null
        if (-not $Running.Done.WaitOne([TimeSpan]::FromSeconds(60))) { throw 'Packaged fixture did not finish within 60 seconds.' }
        if (-not $Running.Process.WaitForExit(10000)) { throw 'Packaged fixture did not exit after signalling completion.' }
        $result = Get-Content -Raw -LiteralPath $Running.Result | ConvertFrom-Json
        if (-not $result.Passed) { throw "Packaged fixture failed before exit: $($result.Error)" }
    } finally {
        $Running.Ready.Dispose(); $Running.Release.Dispose(); $Running.Done.Dispose(); $Running.Process.Dispose()
    }
}

function Start-InstalledApplication($Registration, $Running, [string] $Phase) {
    $launcher = Join-Path $Registration.Package.InstallLocation 'bin/GameTier.exe'
    $process = Get-Process -Id $Running.ApplicationProcessId -ErrorAction Stop
    try {
        if ($process.Path -ine $launcher) { throw "Installed $Phase application started an unexpected executable: $($process.Path)" }
        . (Join-Path $PSScriptRoot 'fixtures/Wait-GameTierWindow.ps1')
        Wait-GameTierWindow -ProcessId $process.Id | Out-Null
        $process.Refresh()
        Assert-Equal $process.HasExited $false "Installed $Phase application remains running"
        $packageData = Join-Path $env:LOCALAPPDATA "Packages\$($Registration.Package.PackageFamilyName)"
        if (Test-Path -LiteralPath $packageData -PathType Container) {
            $privateSettings = @(Get-ChildItem -LiteralPath $packageData -Directory -Recurse -Filter 'GameTierData' -Force)
            Assert-Equal $privateSettings.Count 0 "Installed $Phase application uses persistent public settings"
        }
        return $process
    } catch {
        if (-not $process.HasExited) { $process.CloseMainWindow() | Out-Null }
        $process.Dispose()
        throw
    }
}

function Stop-InstalledApplication([Diagnostics.Process] $Process) {
    if ($null -eq $Process) { return }
    try {
        $Process.Refresh()
        if (-not $Process.HasExited) {
            $Process.CloseMainWindow() | Out-Null
            $closed = $Process.WaitForExit(5000)
            if (-not $closed) {
                Write-Output "Ending disposable GUI fixture $($Process.Id) after its close request; update deferral was checked while it remained running."
                $Process.Kill()
                if (-not $Process.WaitForExit(10000)) { throw 'Owned GUI fixture did not exit during cleanup.' }
            }
        }
    } finally { if ($Process.HasExited) { $Process.Dispose() } }
}

$classId = '{9B6ED281-384A-4DBF-98C0-D26B6D41B352}'
$registrationKeys = @("Software\Classes\CLSID\$classId", 'Software\Classes\*\shell\GameTier.Promote', 'Software\Classes\Directory\shell\GameTier.Promote')
$preferenceKey = 'Software\GameTier'
$preferenceName = 'ExplorerIntegrationEnabled'
$legacySettingsDirectory = Join-Path $env:LOCALAPPDATA 'GameTier'
$settingsDirectory = Join-Path $env:LOCALAPPDATA 'GameTierData'
$settingsFile = Join-Path $settingsDirectory 'settings.preferences_pb'
$initial = Read-PackageIdentity $Package
$upgrade = Read-PackageIdentity $UpgradePackage
Assert-Equal $initial.Name 'GameTier' 'Test package identity'
Assert-Equal $upgrade.Name $initial.Name 'Upgrade package identity'
Assert-Equal $upgrade.Publisher $initial.Publisher 'Upgrade publisher'
Assert-Equal $upgrade.Architecture $initial.Architecture 'Upgrade architecture'
if ($upgrade.Version -le $initial.Version) { throw 'Upgrade package must have a higher MSIX version.' }
if (-not (Test-Path -LiteralPath $Certificate -PathType Leaf)) { throw "Signing certificate is missing: $Certificate" }
$testCertificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new((Resolve-Path -LiteralPath $Certificate).Path)
Assert-Equal $initial.Publisher $testCertificate.Subject 'Test certificate publisher'
Assert-CleanProfile

$temporaryRoot = if ([string]::IsNullOrWhiteSpace($env:RUNNER_TEMP)) { [IO.Path]::GetTempPath() } else { $env:RUNNER_TEMP }
$fixture = Join-Path $temporaryRoot "gametier-update-lifecycle-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $fixture | Out-Null
$fixtureScript = Join-Path $fixture 'PackagedFixture.ps1'
$settingsFixture = Join-Path $fixture 'settings.preferences_pb'
$settingsBytes = [Convert]::FromBase64String((Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'fixtures/settings.preferences_pb.base64')).Trim())
[IO.File]::WriteAllBytes($settingsFixture, $settingsBytes)
$settingsHash = (Get-FileHash -LiteralPath $settingsFixture).Hash
@'
param([string] $EventPrefix, [string] $Result, [string] $Settings, [string] $SettingsFixture, [string] $LinkRoot, [string] $Launcher, [int] $Initialize)
$ErrorActionPreference = 'Stop'
$ready = [Threading.EventWaitHandle]::OpenExisting("$EventPrefix-ready")
$release = [Threading.EventWaitHandle]::OpenExisting("$EventPrefix-release")
$done = [Threading.EventWaitHandle]::OpenExisting("$EventPrefix-done")
$application = $null
try {
    Add-Type -TypeDefinition @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class PackageIdentity {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    public static extern int GetCurrentPackageFullName(ref int length, StringBuilder name);
    public static string Current() {
        int length = 0;
        int result = GetCurrentPackageFullName(ref length, null);
        if (result != 122) throw new InvalidOperationException("Missing package identity: " + result);
        var name = new StringBuilder(length);
        result = GetCurrentPackageFullName(ref length, name);
        if (result != 0) throw new InvalidOperationException("Package identity error: " + result);
        return name.ToString();
    }
}
"@
    $packageName = [PackageIdentity]::Current()
    if ($Initialize) {
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Settings)) | Out-Null
        [IO.File]::WriteAllBytes($Settings, [IO.File]::ReadAllBytes($SettingsFixture))
        $key = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Software\GameTier')
        try { $key.SetValue('ExplorerIntegrationEnabled', 0, [Microsoft.Win32.RegistryValueKind]::DWord) } finally { $key.Dispose() }
    }
    if ((Get-FileHash -LiteralPath $Settings).Hash -cne (Get-FileHash -LiteralPath $SettingsFixture).Hash) { throw 'Packaged settings content differs.' }
    $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Software\GameTier')
    try {
        if ($null -eq $key -or $key.GetValue('ExplorerIntegrationEnabled') -ne 0) { throw 'Packaged disabled preference was lost.' }
    } finally { if ($null -ne $key) { $key.Dispose() } }
    New-Item -ItemType Directory -Path $LinkRoot | Out-Null
    $target = Join-Path $LinkRoot 'target.txt'
    $link = Join-Path $LinkRoot 'link.txt'
    [IO.File]::WriteAllText($target, 'GameTier disposable symlink fixture')
    New-Item -ItemType SymbolicLink -Path $link -Target $target | Out-Null
    if ([IO.File]::ReadAllText($link) -cne [IO.File]::ReadAllText($target)) { throw 'Packaged symlink did not resolve.' }
    Remove-Item -LiteralPath $link
    if (-not (Test-Path -LiteralPath $target)) { throw 'Removing the packaged symlink removed its target.' }
    $logDirectory = [IO.Path]::GetDirectoryName($Result)
    $logPrefix = [IO.Path]::GetFileNameWithoutExtension($Result)
    $application = Start-Process -FilePath $Launcher -WorkingDirectory $logDirectory -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logDirectory "$logPrefix-launcher.stdout.log") `
        -RedirectStandardError (Join-Path $logDirectory "$logPrefix-launcher.stderr.log")
    @{ Passed = $true; ProcessId = $PID; ApplicationProcessId = $application.Id; PackageFullName = $packageName } |
        ConvertTo-Json | Set-Content -LiteralPath $Result
    $ready.Set() | Out-Null
    if (-not $release.WaitOne([TimeSpan]::FromMinutes(5))) { throw 'Parent did not release the packaged fixture within five minutes.' }
} catch {
    @{ Passed = $false; Error = ($_.Exception.ToString() + "`n" + $_.ScriptStackTrace) } | ConvertTo-Json | Set-Content -LiteralPath $Result
    $ready.Set() | Out-Null
    exit 1
} finally {
    if ($null -ne $application) {
        if (-not $application.HasExited) {
            $application.CloseMainWindow() | Out-Null
            $application.WaitForExit(10000) | Out-Null
        }
        $application.Dispose()
    }
    $done.Set() | Out-Null
    $ready.Dispose(); $release.Dispose(); $done.Dispose()
}
'@ | Set-Content -LiteralPath $fixtureScript -Encoding UTF8

$certificateCreated = $false
$installationOwned = $false
$running = $null
$application = $null
$transcriptStarted = $false
try {
    Start-Transcript -LiteralPath (Join-Path $fixture 'lifecycle.log') | Out-Null
    $transcriptStarted = $true
    $certificatePath = "Cert:\LocalMachine\TrustedPeople\$($testCertificate.Thumbprint)"
    if (-not (Test-Path -LiteralPath $certificatePath)) {
        Import-Certificate -FilePath (Resolve-Path -LiteralPath $Certificate).Path -CertStoreLocation 'Cert:\LocalMachine\TrustedPeople' | Out-Null
        $certificateCreated = $true
    }
    $installationOwned = $true
    Add-AppxPackage -Path (Resolve-Path -LiteralPath $Package).Path -DeferRegistrationWhenPackagesAreInUse
    $registration = Assert-Installed $initial
    $running = Start-PackageFixture $registration 'initial' $true
    $application = Start-InstalledApplication $registration $running 'initial'
    Assert-RetainedSettings 'Initial install'
    Add-AppxPackage -Path (Resolve-Path -LiteralPath $UpgradePackage).Path -DeferRegistrationWhenPackagesAreInUse
    $running.Process.Refresh()
    Assert-Equal $running.Process.HasExited $false 'In-use fixture survives deferred upgrade'
    $application.Refresh()
    Assert-Equal $application.HasExited $false 'Running GameTier survives deferred upgrade'
    Assert-Equal ([version](Get-OwnedPackage).Version) $initial.Version 'In-use upgrade remains deferred'
    Stop-InstalledApplication $application
    $application = $null
    Stop-PackageFixture $running
    $running = $null
    Add-AppxPackage -Path (Resolve-Path -LiteralPath $UpgradePackage).Path -DeferRegistrationWhenPackagesAreInUse
    $registration = Assert-Installed $upgrade
    Assert-RetainedSettings 'Upgrade'
    $running = Start-PackageFixture $registration 'upgraded' $false
    $application = Start-InstalledApplication $registration $running 'upgraded'
    Stop-InstalledApplication $application
    $application = $null
    Stop-PackageFixture $running
    $running = $null
    Remove-AppxPackage -Package $registration.Package.PackageFullName
    $installationOwned = $false
    Assert-Equal @(Get-AppxPackage -Name $initial.Name).Count 0 'Uninstalled package registration'
    Assert-RetainedSettings 'Uninstall'
    foreach ($path in $registrationKeys) {
        $key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey($path)
        if ($null -ne $key) { $key.Dispose(); throw "Uninstall retained an Explorer registry key: $path" }
    }
    Write-Output 'Conveyor lifecycle passed: signed install, real GUI launch, packaged symlink, deferred in-use upgrade, disabled preference and settings retained after upgrade and uninstall.'
} finally {
    try {
        try {
            if ($null -ne $application) { Stop-InstalledApplication $application }
        } finally {
            try {
                if ($null -ne $running) { Stop-PackageFixture $running }
            } finally {
                if ($installationOwned) {
                    foreach ($owned in @(Get-AppxPackage -Name $initial.Name | Where-Object { $_.Publisher -ceq $initial.Publisher })) {
                        Remove-AppxPackage -Package $owned.PackageFullName
                    }
                }
            }
        }
    } finally {
        if ($certificateCreated) { Remove-Item -LiteralPath $certificatePath }
        $testCertificate.Dispose()
        if ($transcriptStarted) { Stop-Transcript | Out-Null }
        Write-Output "Lifecycle logs and retained test data: $fixture; $settingsDirectory; HKCU\$preferenceKey"
    }
}
