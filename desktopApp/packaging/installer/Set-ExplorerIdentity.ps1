[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Register', 'Remove')][string] $Action,
    [Parameter(Mandatory)][string] $Publisher,
    [string] $Package,
    [string] $InstallDirectory
)

$ErrorActionPreference = 'Stop'
$existing = @(Get-AppxPackage -Name 'GameTier.Explorer')
if (@($existing | Where-Object { $_.Publisher -cne $Publisher }).Count -ne 0) {
    throw 'An Explorer identity with a different publisher is already registered.'
}
if ($Action -eq 'Remove') {
    foreach ($identity in $existing) { Remove-AppxPackage -Package $identity.PackageFullName }
    return
}
if (-not $Package -or -not $InstallDirectory) { throw 'Registration requires a signed package and the installed application directory.' }
$Package = (Resolve-Path -LiteralPath $Package).Path
$InstallDirectory = (Resolve-Path -LiteralPath $InstallDirectory).Path
foreach ($file in @('GameTier.exe', 'GameTierExplorer.dll')) {
    if (-not (Test-Path -LiteralPath (Join-Path $InstallDirectory $file) -PathType Leaf)) { throw "Installed application file is missing: $file" }
}
$signature = Get-AuthenticodeSignature -LiteralPath $Package
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -cne $Publisher) {
    throw 'The identity package must have a trusted signature matching the publisher.'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($Package)
try {
    $entry = $archive.GetEntry('AppxManifest.xml')
    if ($null -eq $entry) { throw 'The identity package manifest is missing.' }
    $stream = $entry.Open()
    try {
        $manifest = New-Object Xml.XmlDocument
        $manifest.Load($stream)
        if ($manifest.Package.Identity.Name -cne 'GameTier.Explorer' -or $manifest.Package.Identity.Publisher -cne $Publisher) {
            throw 'The package is not the expected GameTier Explorer identity.'
        }
    } finally { $stream.Dispose() }
    foreach ($name in @('StoreLogo.png', 'Square44x44Logo.png', 'Square150x150Logo.png')) {
        $entry = $archive.GetEntry("Assets/$name")
        if ($null -eq $entry) { throw "The identity package asset is missing: $name" }
        $target = Join-Path $InstallDirectory "Assets/$name"
        if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
            throw "Install the signed package's Assets directory beside GameTier.exe before registering identity: $name"
        }
        $stream = $entry.Open()
        $algorithm = [Security.Cryptography.SHA256]::Create()
        try {
            $expectedHash = [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-', '')
            if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -cne $expectedHash) {
                throw "Installed identity asset differs from the signed package: $name"
            }
        } finally { $algorithm.Dispose(); $stream.Dispose() }
    }
} finally { $archive.Dispose() }
Add-AppxPackage -Path $Package -ExternalLocation $InstallDirectory
$registered = @(Get-AppxPackage -Name 'GameTier.Explorer' | Where-Object { $_.Publisher -ceq $Publisher })
if ($registered.Count -ne 1) { throw 'Explorer package registration did not produce the expected identity.' }
