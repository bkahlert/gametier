Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-ElevatedVolumeHelper([string] $Action, [string] $FixtureDirectory, [string] $UserSid) {
    $helper = Join-Path $PSScriptRoot 'VolumeFixtures.ps1'
    $arguments = "-NoLogo -NoProfile -ExecutionPolicy Bypass -File `"$helper`" " +
        "-Action $Action -FixtureDirectory `"$FixtureDirectory`" -UserSid `"$UserSid`""
    Write-Host "$Action disposable filesystem fixtures (Windows may request UAC consent)."
    $process = Start-Process -FilePath "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" `
        -ArgumentList $arguments -Verb RunAs -WindowStyle Hidden -PassThru -Wait
    if ($process.ExitCode -ne 0) {
        throw "$Action helper failed (exit $($process.ExitCode)); fixture directory: $FixtureDirectory"
    }
}

function Invoke-WithTestVolumes {
    [CmdletBinding()]
    param(
        [string[]] $ExistingVolumes,
        [Parameter(Mandatory = $true)]
        [scriptblock] $Action
    )

    $fixture = $null
    $previousVolumes = $env:GAMETIER_TEST_VOLUMES
    $failure = $null
    try {
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
        $userSid = $identity.User.Value
        if ($PSBoundParameters.ContainsKey('ExistingVolumes')) {
            if ($ExistingVolumes.Count -ne 4) { throw 'Supply four roots ordered NTFS1, NTFS2, ReFS1, ReFS2.' }
            $roots = $ExistingVolumes
        } else {
            $fixture = Join-Path ([IO.Path]::GetTempPath()) ("gametier-volumes-" + [Guid]::NewGuid().ToString('N'))
            New-Item -Path $fixture -ItemType Directory | Out-Null
            Invoke-ElevatedVolumeHelper -Action 'Setup' -FixtureDirectory $fixture -UserSid $userSid
            $roots = Get-Content -LiteralPath (Join-Path $fixture 'roots.json') -Raw | ConvertFrom-Json
            if ($roots.Count -ne 4) { throw 'Setup did not return four fixture volumes.' }
        }
        $env:GAMETIER_TEST_VOLUMES = $roots -join ';'
        & $Action
    } catch {
        $failure = $_
    } finally {
        $env:GAMETIER_TEST_VOLUMES = $previousVolumes
        if ($null -ne $fixture) {
            try {
                $images = @(Get-ChildItem -LiteralPath $fixture -Filter '*.vhdx')
                if ($images.Count -gt 0) {
                    Invoke-ElevatedVolumeHelper -Action 'Cleanup' -FixtureDirectory $fixture -UserSid $userSid
                }
                foreach ($name in @('roots.json', 'ownership.json')) {
                    $file = Join-Path $fixture $name
                    if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file }
                }
                Remove-Item -LiteralPath $fixture
            } catch {
                Write-Error "Fixture cleanup failed: $($_.Exception.Message). Remaining fixtures: $fixture" `
                    -ErrorAction Continue
                if ($null -eq $failure) { $failure = $_ }
            }
        }
    }
    if ($null -ne $failure) { throw $failure }
}

Export-ModuleMember -Function Invoke-WithTestVolumes
