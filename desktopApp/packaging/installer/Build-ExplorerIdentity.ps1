[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^\d+\.\d+\.\d+\.\d+$')][string] $PackageVersion,
    [Parameter(Mandatory)][string] $Publisher,
    [string] $CertificateThumbprint,
    [uri] $TimestampUrl,
    [string] $Destination = 'build/explorer-identity',
    [switch] $StageOnly
)

$ErrorActionPreference = 'Stop'
$projectDirectory = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$Destination = [IO.Path]::GetFullPath($Destination)
if (Test-Path -LiteralPath $Destination) { throw "Identity destination already exists: $Destination" }
foreach ($part in $PackageVersion.Split('.')) {
    if ([long]$part -gt 65535) { throw 'Package version fields must be between 0 and 65535.' }
}
if (-not $StageOnly) {
    if (-not $CertificateThumbprint -or -not $TimestampUrl) { throw 'A release certificate thumbprint and timestamp URL are required to sign the identity package.' }
    $certificate = Get-Item -LiteralPath "Cert:\CurrentUser\My\$CertificateThumbprint"
    if (-not $certificate.HasPrivateKey -or $certificate.Subject -cne $Publisher) {
        throw 'The signing certificate must have a private key and match the manifest publisher.'
    }
    $makeappx = (Get-Command makeappx.exe -ErrorAction Stop).Source
    $signtool = (Get-Command signtool.exe -ErrorAction Stop).Source
}

$content = Join-Path $Destination 'content'
$assets = Join-Path $content 'Assets'
New-Item -ItemType Directory -Path $assets | Out-Null
$manifest = New-Object Xml.XmlDocument
$manifest.Load((Join-Path $projectDirectory 'desktopApp/packaging/explorer/AppxManifest.xml.in'))
$manifest.Package.Identity.SetAttribute('Publisher', $Publisher)
$manifest.Package.Identity.SetAttribute('Version', $PackageVersion)
$manifest.Save((Join-Path $content 'AppxManifest.xml'))

Add-Type -AssemblyName System.Drawing
$iconBytes = [IO.File]::ReadAllBytes((Join-Path $projectDirectory 'desktopApp/packaging/GameTier.ico'))
$largestImage = $null
for ($index = 0; $index -lt [BitConverter]::ToUInt16($iconBytes, 4); $index++) {
    $entry = 6 + 16 * $index
    $width = [int]$iconBytes[$entry]
    if ($width -eq 0) { $width = 256 }
    $offset = [BitConverter]::ToInt32($iconBytes, $entry + 12)
    $length = [BitConverter]::ToInt32($iconBytes, $entry + 8)
    if ($iconBytes[$offset] -eq 137 -and ($null -eq $largestImage -or $width -gt $largestImage.Width)) {
        $largestImage = @{ Width = $width; Offset = $offset; Length = $length }
    }
}
if ($null -eq $largestImage) { throw 'The application icon must contain a PNG image for identity assets.' }
$imageStream = New-Object IO.MemoryStream($iconBytes, $largestImage.Offset, $largestImage.Length, $false)
$source = [Drawing.Image]::FromStream($imageStream)
try {
    foreach ($asset in @(@('StoreLogo', 50), @('Square44x44Logo', 44), @('Square150x150Logo', 150))) {
        $bitmap = New-Object Drawing.Bitmap([int]$asset[1], [int]$asset[1])
        $graphics = [Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $graphics.DrawImage($source, 0, 0, $bitmap.Width, $bitmap.Height)
            $bitmap.Save((Join-Path $assets "$($asset[0]).png"), [Drawing.Imaging.ImageFormat]::Png)
        } finally { $graphics.Dispose(); $bitmap.Dispose() }
    }
} finally { $source.Dispose(); $imageStream.Dispose() }
if ($StageOnly) { Write-Output $content; return }

$package = Join-Path $Destination 'GameTier.Explorer.msix'
& $makeappx pack /d $content /p $package /nv
if ($LASTEXITCODE -ne 0) { throw "makeappx failed with exit code $LASTEXITCODE." }
& $signtool sign /fd SHA256 /sha1 $CertificateThumbprint /tr $TimestampUrl.AbsoluteUri /td SHA256 $package
if ($LASTEXITCODE -ne 0) { throw "Identity package signing failed with exit code $LASTEXITCODE." }
& $signtool verify /pa $package
if ($LASTEXITCODE -ne 0) { throw 'Identity package signature is not trusted by the current Windows installation.' }
Write-Output $package
