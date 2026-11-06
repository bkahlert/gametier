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
