param(
    [string[]] $ExistingVolumes
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-ElevatedVolumeHelper([string] $Action) {
    $helper = Join-Path $PSScriptRoot 'FileMoveVolumes.ps1'
    $arguments = "-NoLogo -NoProfile -ExecutionPolicy Bypass -File `"$helper`" " +
        "-Action $Action -FixtureDirectory `"$fixture`" -UserSid `"$userSid`""
    Write-Host "$Action disposable filesystem fixtures (Windows may request UAC consent)."
    $process = Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" `
        -ArgumentList $arguments -Verb RunAs -PassThru -Wait
    if ($process.ExitCode -ne 0) {
        throw "$Action helper failed (exit $($process.ExitCode)); fixture directory: $fixture"
    }
}

$fixture = $null
$previousVolumes = $env:GAMETIER_TEST_VOLUMES
$failure = $null
try {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $userSid = $identity.User.Value
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Run this command from an unelevated shell so move tests exercise ordinary user permissions.'
    }

    if ($ExistingVolumes) {
        if ($ExistingVolumes.Count -ne 4) { throw 'Supply four roots ordered NTFS1, NTFS2, ReFS1, ReFS2.' }
        $roots = $ExistingVolumes
    } else {
        $fixture = Join-Path ([IO.Path]::GetTempPath()) ("gametier-volumes-" + [Guid]::NewGuid().ToString('N'))
        New-Item -Path $fixture -ItemType Directory | Out-Null
        Invoke-ElevatedVolumeHelper 'Setup'
        $roots = Get-Content -LiteralPath (Join-Path $fixture 'roots.json') -Raw | ConvertFrom-Json
        if ($roots.Count -ne 4) { throw 'Setup did not return four fixture volumes.' }
    }
    $env:GAMETIER_TEST_VOLUMES = $roots -join ';'
    $project = Split-Path -Parent $PSScriptRoot
    & (Join-Path $project 'kotlin.bat') test -m shared "--project-dir=$project"
    if ($LASTEXITCODE -ne 0) { throw "File move tests failed (exit $LASTEXITCODE)." }
} catch {
    $failure = $_
    Write-Error -ErrorRecord $_ -ErrorAction Continue
} finally {
    $env:GAMETIER_TEST_VOLUMES = $previousVolumes
    if ($null -ne $fixture) {
        try {
            $images = @(Get-ChildItem -LiteralPath $fixture -Filter '*.vhdx')
            if ($images.Count -gt 0) { Invoke-ElevatedVolumeHelper 'Cleanup' }
            $rootsFile = Join-Path $fixture 'roots.json'
            if (Test-Path -LiteralPath $rootsFile) { Remove-Item -LiteralPath $rootsFile }
            $ownershipFile = Join-Path $fixture 'ownership.json'
            if (Test-Path -LiteralPath $ownershipFile) { Remove-Item -LiteralPath $ownershipFile }
            Remove-Item -LiteralPath $fixture
        } catch {
            Write-Error "Fixture cleanup failed: $($_.Exception.Message). Remaining fixtures: $fixture" `
                -ErrorAction Continue
            if ($null -eq $failure) { $failure = $_ }
        }
    }
}
if ($null -ne $failure) { exit 1 }
