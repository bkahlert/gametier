param(
    [Parameter(Mandatory = $true)]
    [string] $Configuration
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

try {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Successful symlink tests require an elevated test process.'
    }
    $options = Get-Content -LiteralPath $Configuration -Raw | ConvertFrom-Json
    $env:GAMETIER_TEST_VOLUMES = $options.Volumes -join ';'
    $project = Split-Path -Parent $PSScriptRoot
    & (Join-Path $project 'kotlin.bat') test -m shared "--project-dir=$project" `
        '--include-tag=symlink-success' '--jvm-args=-Dgametier.test.requireSymlinks=true' `
        *> $options.Output
    if ($LASTEXITCODE -ne 0) { throw "Elevated symlink tests failed (exit $LASTEXITCODE)." }
} catch {
    Write-Error -ErrorRecord $_ -ErrorAction Continue
    exit 1
}
