[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $Site,
    [Parameter(Mandatory)][string] $ExplorerLibrary,
    [Parameter(Mandatory)][ValidatePattern('^\d+\.\d+\.\d+$')][string] $AppVersion
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Assert-Equal($Actual, $Expected, [string] $Description) {
    if ($Actual -cne $Expected) { throw "$Description expected '$Expected', got '$Actual'." }
}

function Get-Single($Values, [string] $Description) {
    $items = @($Values)
    if ($items.Count -ne 1) { throw "$Description expected one item, got $($items.Count)." }
    return $items[0]
}

function Read-Entry([IO.Compression.ZipArchiveEntry] $Entry) {
    $reader = [IO.StreamReader]::new($Entry.Open())
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}

function Get-Entry([string] $Name) {
    return Get-Single @($archive.Entries | Where-Object { $_.FullName.Replace('\', '/') -ieq $Name }) "MSIX entry $Name"
}

function Assert-Signed([string] $Path, [string] $Thumbprint) {
    $signature = Get-AuthenticodeSignature -LiteralPath $Path
    if ($signature.Status -notin @('Valid', 'NotTrusted') -or $null -eq $signature.SignerCertificate) {
        throw "Invalid Authenticode signature on '$Path': $($signature.Status) / $($signature.StatusMessage)"
    }
    Assert-Equal $signature.SignerCertificate.Thumbprint $Thumbprint "Signing certificate for $([IO.Path]::GetFileName($Path))"
}

function Assert-StableUri([string] $Value, [string] $Description) {
    $uri = $null
    if (-not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -cne 'https' -or $uri.Host -ine 'github.com' -or
        $uri.AbsolutePath -notmatch '^/[^/]+/[^/]+/releases/latest/download/[^/]+$' -or
        $uri.Query -or $uri.Fragment) {
        throw "$Description must use the stable HTTPS GitHub /releases/latest/download URL; got '$Value'."
    }
    return $uri
}

if (-not (Test-Path -LiteralPath $Site -PathType Container)) { throw "Update site directory is missing: $Site" }
if (-not (Test-Path -LiteralPath $ExplorerLibrary -PathType Leaf)) { throw "Explorer build is missing: $ExplorerLibrary" }
$sitePath = (Resolve-Path -LiteralPath $Site).Path
$files = @(Get-ChildItem -LiteralPath $sitePath -File)
$package = Get-Single @($files | Where-Object Extension -eq '.msix') 'Windows MSIX package'
$installer = Get-Single @($files | Where-Object Extension -eq '.exe') 'Windows EXE installer'
$appInstaller = Get-Single @($files | Where-Object Extension -eq '.appinstaller') 'App Installer update metadata'
$certificateFile = Get-Single @($files | Where-Object Extension -in @('.cer', '.crt')) 'Windows signing certificate'
$certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new($certificateFile.FullName)
$download = Get-Single @($files | Where-Object Name -ceq 'download.html') 'Download page'
if ($installer.Length -eq 0 -or $download.Length -eq 0) { throw 'Installer and download page must not be empty.' }
$metadata = @($files | Where-Object { $_.Extension -in @('.json', '.properties', '.txt', '.xml') -and $_.Length -gt 0 })
if ($metadata.Count -eq 0) { throw 'Conveyor update-site metadata is missing.' }

$fixture = Join-Path ([IO.Path]::GetTempPath()) "gametier-package-check-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $fixture | Out-Null
$archive = [IO.Compression.ZipFile]::OpenRead($package.FullName)
try {
    $duplicates = @($archive.Entries | Group-Object { $_.FullName.Replace('\', '/').ToLowerInvariant() } | Where-Object Count -gt 1)
    if ($duplicates.Count -gt 0) { throw "Ambiguous MSIX entries: $($duplicates.Name -join ', ')" }
    [xml] $manifest = Read-Entry (Get-Entry 'AppxManifest.xml')
    $ns = [Xml.XmlNamespaceManager]::new($manifest.NameTable)
    $namespaces = @{
        f = 'http://schemas.microsoft.com/appx/manifest/foundation/windows10'
        com = 'http://schemas.microsoft.com/appx/manifest/com/windows10'
        d4 = 'http://schemas.microsoft.com/appx/manifest/desktop/windows10/4'
        d5 = 'http://schemas.microsoft.com/appx/manifest/desktop/windows10/5'
        d6 = 'http://schemas.microsoft.com/appx/manifest/desktop/windows10/6'
        v = 'http://schemas.microsoft.com/appx/manifest/virtualization/windows10'
        r = 'http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities'
    }
    foreach ($prefix in $namespaces.Keys) { $ns.AddNamespace($prefix, $namespaces[$prefix]) }
    $identity = Get-Single @($manifest.SelectNodes('/f:Package/f:Identity', $ns)) 'MSIX identity'
    Assert-Equal $identity.GetAttribute('Name') 'GameTier' 'Package identity'
    Assert-Equal $identity.GetAttribute('Version') "$AppVersion.0" 'Package version'
    Assert-Equal $identity.GetAttribute('ProcessorArchitecture') 'x64' 'Package architecture'
    Assert-Equal $identity.GetAttribute('Publisher') $certificate.Subject 'Package publisher'
    $application = Get-Single @($manifest.SelectNodes('/f:Package/f:Applications/f:Application', $ns)) 'Application'
    Assert-Equal $application.GetAttribute('Id') 'GameTier' 'Application identity'
    Assert-Equal $application.GetAttribute('Executable') 'GameTier.exe' 'Launcher path'
    $packageLogo = Get-Single @($manifest.SelectNodes('/f:Package/f:Properties/f:Logo', $ns)) 'Package logo'
    $logoPaths = @($packageLogo.InnerText.Trim()) + @($manifest.SelectNodes('//@*') |
        Where-Object { $_.LocalName -like '*Logo' } | ForEach-Object Value)
    foreach ($logoPath in $logoPaths) {
        $normalizedLogo = $logoPath.Replace('\', '/')
        $logoExtension = [IO.Path]::GetExtension($normalizedLogo)
        $logoStem = $normalizedLogo.Substring(0, $normalizedLogo.Length - $logoExtension.Length)
        $logos = @($archive.Entries | Where-Object {
            $_.Length -gt 0 -and ($_.FullName -ieq $normalizedLogo -or
                $_.FullName -imatch ('^' + [regex]::Escape($logoStem) + '\.[^/]+(?:' + [regex]::Escape($logoExtension) + ')$'))
        })
        if ($logos.Count -eq 0) { throw "Referenced package logo is missing: $logoPath" }
    }
    $classId = '9B6ED281-384A-4DBF-98C0-D26B6D41B352'
    $class = Get-Single @($manifest.SelectNodes('//com:Extension[@Category="windows.comServer"]/com:ComServer/com:SurrogateServer/com:Class', $ns) |
        Where-Object { $_.GetAttribute('Id').Trim('{}') -ieq $classId }) 'Packaged Explorer COM class'
    Assert-Equal $class.GetAttribute('Path') 'GameTierExplorer.dll' 'Explorer COM path'
    Assert-Equal $class.GetAttribute('ThreadingModel') 'STA' 'Explorer COM threading model'
    foreach ($kind in @('*', 'Directory')) {
        $item = Get-Single @($manifest.SelectNodes('//d4:Extension[@Category="windows.fileExplorerContextMenus"]/d4:FileExplorerContextMenus/d5:ItemType', $ns) |
            Where-Object { $_.GetAttribute('Type') -ceq $kind }) "Explorer item type $kind"
        $verb = Get-Single @($item.SelectNodes('d5:Verb', $ns)) "Explorer verb for $kind"
        Assert-Equal $verb.GetAttribute('Clsid').Trim('{}').ToUpperInvariant() $classId "Explorer command for $kind"
    }
    foreach ($capability in @('runFullTrust', 'allowElevation', 'unvirtualizedResources')) {
        Get-Single @($manifest.SelectNodes('/f:Package/f:Capabilities/r:Capability', $ns) |
            Where-Object { $_.GetAttribute('Name') -ceq $capability }) "Restricted capability $capability" | Out-Null
    }
    $excluded = @($manifest.SelectNodes('/f:Package/f:Properties/v:FileSystemWriteVirtualization/v:ExcludedDirectories/v:ExcludedDirectory', $ns) |
        ForEach-Object { $_.InnerText.Trim().Replace('/', '\') })
    if ($excluded -inotcontains '$(KnownFolder:LocalAppData)\GameTierData') { throw 'GameTierData must be excluded from MSIX filesystem virtualization.' }
    $fallback = Get-Single @($manifest.SelectNodes('/f:Package/f:Properties/d6:FileSystemWriteVirtualization', $ns)) 'Windows 10 filesystem virtualization policy'
    Assert-Equal $fallback.InnerText.Trim() 'disabled' 'Windows 10 filesystem virtualization policy'
    $registryPolicy = Get-Single @($manifest.SelectNodes('/f:Package/f:Properties/d6:RegistryWriteVirtualization', $ns)) 'Registry virtualization policy'
    Assert-Equal $registryPolicy.InnerText.Trim() 'disabled' 'Registry virtualization policy'

    foreach ($name in @('GameTier.exe', 'GameTierExplorer.dll')) {
        $entry = Get-Entry $name
        if ($entry.Length -eq 0) { throw "Empty packaged binary: $name" }
        $extracted = Join-Path $fixture $name
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $extracted)
    }
    $packagedLibrary = [IO.File]::ReadAllBytes((Join-Path $fixture 'GameTierExplorer.dll'))
    $builtLibrary = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $ExplorerLibrary).Path)
    foreach ($bytes in @($packagedLibrary, $builtLibrary)) {
        if ($bytes.Length -lt 64 -or $bytes[0] -ne 0x4d -or $bytes[1] -ne 0x5a -or
            [Text.Encoding]::ASCII.GetString($bytes) -notmatch 'DllGetClassObject') {
            throw 'Explorer library must be a PE DLL exporting DllGetClassObject.'
        }
    }
    $license = Read-Entry (Get-Entry 'GameTierExplorer.LICENSE.txt')
    $expectedLicense = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $PSScriptRoot '../explorer/third_party/nlohmann/LICENSE.MIT')
    Assert-Equal $license.Replace("`r`n", "`n").Trim() $expectedLicense.Replace("`r`n", "`n").Trim() 'Explorer dependency license'
    $appLicense = Read-Entry (Get-Entry 'LICENSE')
    $expectedAppLicense = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $PSScriptRoot '../../../LICENSE')
    Assert-Equal $appLicense.Replace("`r`n", "`n").Trim() $expectedAppLicense.Replace("`r`n", "`n").Trim() 'Application license'
    Get-Entry 'AppxSignature.p7x' | Out-Null
    Get-Entry 'AppxBlockMap.xml' | Out-Null

    $launcherBytes = [IO.File]::ReadAllBytes((Join-Path $fixture 'GameTier.exe'))
    $launcherText = [Text.Encoding]::UTF8.GetString($launcherBytes)
    $appPathOptions = @([regex]::Matches($launcherText, '-Dgametier[.]appPath=([^\x00\r\n]+)') | ForEach-Object { $_.Groups[1].Value })
    $appPathOption = Get-Single $appPathOptions 'Generated launcher gametier.appPath option'
    Assert-Equal $appPathOption '&&/../GameTier.exe' 'Launcher path relative to app/ runtime directory'
    if ($launcherText -match '-Djpackage\.app-path=') { throw 'Generated launcher configuration retains a stale jpackage path.' }
    $jars = @($archive.Entries | Where-Object { $_.FullName -match '^app/[^/]+\.jar$' })
    if ($jars.Count -eq 0) { throw 'Application runtime JARs are missing from app/.' }

    [xml] $updates = Get-Content -Raw -Encoding UTF8 -LiteralPath $appInstaller.FullName
    $updateNs = [Xml.XmlNamespaceManager]::new($updates.NameTable)
    $updateNs.AddNamespace('a', $updates.DocumentElement.NamespaceURI)
    if ($updates.DocumentElement.NamespaceURI -notmatch '^http://schemas.microsoft.com/appx/appinstaller/20\d\d$') { throw 'Unexpected App Installer XML namespace.' }
    $rootUri = Assert-StableUri $updates.DocumentElement.GetAttribute('Uri') 'App Installer URI'
    Assert-Equal ([Uri]::UnescapeDataString([IO.Path]::GetFileName($rootUri.AbsolutePath))) $appInstaller.Name 'App Installer URL filename'
    $main = Get-Single @($updates.SelectNodes('/a:AppInstaller/a:MainPackage', $updateNs)) 'App Installer main package'
    foreach ($attribute in @('Name', 'Publisher', 'Version', 'ProcessorArchitecture')) {
        Assert-Equal $main.GetAttribute($attribute) $identity.GetAttribute($attribute) "App Installer $attribute"
    }
    $packageUri = Assert-StableUri $main.GetAttribute('Uri') 'Package URI'
    Assert-Equal ([Uri]::UnescapeDataString([IO.Path]::GetFileName($packageUri.AbsolutePath))) $package.Name 'Package URL filename'
    Assert-Equal ($packageUri.AbsoluteUri.Substring(0, $packageUri.AbsoluteUri.LastIndexOf('/'))) ($rootUri.AbsoluteUri.Substring(0, $rootUri.AbsoluteUri.LastIndexOf('/'))) 'Update site base URL'
    Get-Single @($updates.SelectNodes('/a:AppInstaller/a:UpdateSettings/a:AutomaticBackgroundTask', $updateNs)) 'Automatic background updates' | Out-Null
    foreach ($node in @($updates.SelectNodes('//*'))) {
        if ($node.LocalName -in @('ForceUpdateFromAnyVersion', 'ForceApplicationShutdown', 'ForceTargetApplicationShutdown', 'UpdateBlocksActivation') -and
            $node.InnerText.Trim() -ine 'false') { throw "Unsafe App Installer update setting: $($node.LocalName)=$($node.InnerText)" }
        foreach ($attribute in @($node.Attributes)) {
            if ($attribute.LocalName -in @('ForceUpdateFromAnyVersion', 'ForceApplicationShutdown', 'ForceTargetApplicationShutdown', 'UpdateBlocksActivation') -and
                $attribute.Value -ine 'false') { throw "Unsafe App Installer update setting: $($attribute.Name)=$($attribute.Value)" }
        }
    }
    $downloadHtml = Get-Content -Raw -Encoding UTF8 -LiteralPath $download.FullName
    if ($downloadHtml -notmatch [regex]::Escape($installer.Name)) { throw 'Download page does not reference the generated Windows EXE installer.' }
    foreach ($name in @('GameTier.exe', 'GameTierExplorer.dll')) {
        Assert-Signed (Join-Path $fixture $name) $certificate.Thumbprint
    }
    Write-Output "Conveyor update site passed: $($package.Name), signed payload, Explorer registration, persistent settings, stable background updates."
} finally {
    $archive.Dispose()
    $certificate.Dispose()
    $resolvedFixture = [IO.Path]::GetFullPath($fixture)
    if ($resolvedFixture.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
    }
}
