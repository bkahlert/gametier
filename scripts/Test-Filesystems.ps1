<#
.SYNOPSIS
Runs the shared tests against real NTFS and ReFS volumes.
.DESCRIPTION
Run from an unelevated PowerShell. Windows requests UAC consent to set up and
clean up disposable volumes, and to run successful-symlink tests separately.
Other tests run with your ordinary permissions. No Developer Mode or user-rights
settings are changed.

Requires a Windows edition/build that can create ReFS volumes. By default the
runner creates two NTFS and two ReFS dynamic VHDX images, each with 64 GiB virtual
capacity; actual disk use grows with formatting and test data. Only newly created
images are formatted. Neither Hyper-V nor a third-party RAM-disk driver is needed.

Cleanup is attempted after failures; any remaining fixture directory is reported.
Unattended CI must provision elevated setup/cleanup separately because UAC prompts
cannot be answered noninteractively. Successful-symlink tests require permission
to create symbolic links; ordinary direct test runs report an unmet prerequisite
when that permission is unavailable.
.PARAMETER ExistingVolumes
Four writable roots on distinct volumes, ordered NTFS1, NTFS2, ReFS1, ReFS2.
This mode creates and removes only uniquely named test directories within those
roots; it does not format the volumes. The separate symlink phase still uses UAC.
.EXAMPLE
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Filesystems.ps1
Provisions disposable volumes, runs the tests, then cleans up.
.EXAMPLE
.\scripts\Test-Filesystems.ps1 -ExistingVolumes 'F:\tests','G:\tests','H:\tests','I:\tests'
Uses four existing volumes in NTFS1, NTFS2, ReFS1, ReFS2 order.
.NOTES
For direct test runs, set GAMETIER_TEST_VOLUMES to four semicolon-separated roots
in the same order, then run .\kotlin.bat test -m shared. Provisioning, cleanup, and
symlink privileges are your responsibility in this mode.

TestVolumes.psm1 provides Invoke-WithTestVolumes for other test workflows.
Test-FileMoves.ps1 is a compatibility entry point to this runner.
#>
param(
    [string[]] $ExistingVolumes
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'TestVolumes.psm1') -Force

function Invoke-ElevatedSymlinkTests {
    $directory = Join-Path ([IO.Path]::GetTempPath()) ("gametier-symlink-tests-" + [Guid]::NewGuid().ToString('N'))
    New-Item -Path $directory -ItemType Directory | Out-Null
    $configuration = Join-Path $directory 'configuration.json'
    $output = Join-Path $directory 'tests.log'
    try {
        @{
            Volumes = @($env:GAMETIER_TEST_VOLUMES -split ';')
            Output = $output
        } | ConvertTo-Json | Set-Content -LiteralPath $configuration
        $helper = Join-Path $PSScriptRoot 'Test-ElevatedSymlinks.ps1'
        $arguments = "-NoLogo -NoProfile -ExecutionPolicy Bypass -File `"$helper`" -Configuration `"$configuration`""
        Write-Host 'Run successful symlink tests elevated (Windows may request UAC consent).'
        $process = Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" `
            -ArgumentList $arguments -Verb RunAs -WindowStyle Hidden -PassThru -Wait
        if (Test-Path -LiteralPath $output) { Get-Content -LiteralPath $output }
        if ($process.ExitCode -ne 0) { throw "Elevated symlink test helper failed (exit $($process.ExitCode))." }
    } finally {
        foreach ($file in @($configuration, $output)) {
            if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file }
        }
        Remove-Item -LiteralPath $directory
    }
}

$volumeOptions = @{}
if ($PSBoundParameters.ContainsKey('ExistingVolumes')) { $volumeOptions.ExistingVolumes = $ExistingVolumes }
$project = Split-Path -Parent $PSScriptRoot
try {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Run this command from an unelevated shell so filesystem tests exercise ordinary user permissions.'
    }
    Invoke-WithTestVolumes @volumeOptions -Action {
        & (Join-Path $project 'kotlin.bat') test -m shared "--project-dir=$project" '--exclude-tag=symlink-success'
        if ($LASTEXITCODE -ne 0) { throw "Filesystem tests failed (exit $LASTEXITCODE)." }
        Invoke-ElevatedSymlinkTests
    }
} catch {
    Write-Error -ErrorRecord $_ -ErrorAction Continue
    exit 1
}
