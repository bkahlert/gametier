param(
    [string[]] $ExistingVolumes
)

& (Join-Path $PSScriptRoot 'Test-Filesystems.ps1') @PSBoundParameters
exit $LASTEXITCODE