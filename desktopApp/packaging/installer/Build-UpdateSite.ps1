[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$')][string] $AppVersion,
    [string] $InputDirectory = 'build/tasks/_desktopApp_stageInstaller@packaging',
    [string] $ExplorerLibrary = 'build/explorer/Release/GameTierExplorer.dll',
    [string] $Destination = 'build/release',
    [uri] $UpdateUrl = 'https://github.com/bkahlert/gametier/releases/latest/download',
    [string] $Conveyor = 'conveyor',
    [switch] $TestPackage,
    [switch] $RequireSigningKey,
    [switch] $StageOnly
)

$ErrorActionPreference = 'Stop'
foreach ($part in $AppVersion.Split('.')) {
    if ([long]$part -gt 65535) { throw 'Package version fields must be between 0 and 65535.' }
}
if (-not $UpdateUrl.IsAbsoluteUri -or ($UpdateUrl.Scheme -ne 'https' -and
        -not ($UpdateUrl.Scheme -eq 'http' -and $UpdateUrl.IsLoopback))) {
    throw 'The update site must use HTTPS or localhost HTTP.'
}
if ($RequireSigningKey -and [string]::IsNullOrWhiteSpace($env:CONVEYOR_SIGNING_KEY)) {
    throw 'Set CONVEYOR_SIGNING_KEY to the persistent release key before packaging.'
}
$Destination = [IO.Path]::GetFullPath($Destination)
if (Test-Path -LiteralPath $Destination) { throw "Update-site destination already exists: $Destination" }
if (-not (Test-Path -LiteralPath (Join-Path $InputDirectory 'GameTier.jar') -PathType Leaf)) {
    throw 'Stage GameTier.jar before packaging: kotlin.bat do stageInstaller -m desktopApp'
}
if (-not (Test-Path -LiteralPath $ExplorerLibrary -PathType Leaf)) { throw 'Build GameTierExplorer.dll before packaging.' }
$InputDirectory = (Resolve-Path -LiteralPath $InputDirectory).Path
$ExplorerLibrary = (Resolve-Path -LiteralPath $ExplorerLibrary).Path
$projectDirectory = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$configuration = [ordered]@{
    version = $AppVersion
    inputs = @((Join-Path $InputDirectory '*.jar').Replace('\', '/'))
    site = @{
        'base-url' = $UpdateUrl.AbsoluteUri.TrimEnd('/')
        'consistency-checks' = $(if ($TestPackage) { 'warn' } else { 'error' })
    }
    windows = @{ amd64 = @{ 'package-extras' = @(
        $ExplorerLibrary.Replace('\', '/'),
        (Join-Path $projectDirectory 'LICENSE').Replace('\', '/'),
        @{ from = (Join-Path $projectDirectory 'desktopApp/packaging/explorer/third_party/nlohmann/LICENSE.MIT').Replace('\', '/'); to = 'GameTierExplorer.LICENSE.txt' }
    ) } }
}
New-Item -ItemType Directory -Path $Destination | Out-Null
$configFile = Join-Path $Destination 'build.conveyor.conf'
$baseFile = (Join-Path $PSScriptRoot 'conveyor.conf').Replace('\', '/') | ConvertTo-Json -Compress
$json = $configuration | ConvertTo-Json -Depth 8
$utf8 = New-Object Text.UTF8Encoding($false)
[IO.File]::WriteAllText($configFile, "include required($baseFile)`napp = $json`n", $utf8)
if ($StageOnly) { Write-Output $configFile; return }
$site = Join-Path $Destination 'site'
& $Conveyor --agree-to-license=1 --console=plain --parallelism=2 -f $configFile make site --output-dir $site
if ($LASTEXITCODE -ne 0) { throw "Conveyor failed with exit code $LASTEXITCODE." }
& (Join-Path $PSScriptRoot 'Test-UpdateSite.ps1') -Site $site -ExplorerLibrary $ExplorerLibrary -AppVersion $AppVersion
Write-Output $site
