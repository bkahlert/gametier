[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$')][string] $AppVersion,
    [string] $InputDirectory = 'build/tasks/_desktopApp_stageInstaller@packaging',
    [string] $ExplorerLibrary = 'build/explorer/Release/GameTierExplorer.dll',
    [string] $SettingsLibrary = 'build/explorer/installer/Release/GameTierInstaller.dll',
    [string] $Destination = 'build/release'
)

$ErrorActionPreference = 'Stop'
$projectDirectory = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$InputDirectory = (Resolve-Path -LiteralPath $InputDirectory).Path
$ExplorerLibrary = (Resolve-Path -LiteralPath $ExplorerLibrary).Path
$SettingsLibrary = (Resolve-Path -LiteralPath $SettingsLibrary).Path
if (-not (Test-Path -LiteralPath (Join-Path $InputDirectory 'GameTier.jar') -PathType Leaf)) {
    throw "Stage GameTier.jar before packaging: kotlin.bat do stageInstaller -m desktopApp"
}
if (-not (Test-Path -LiteralPath $ExplorerLibrary -PathType Leaf)) { throw 'Build GameTierExplorer.dll before packaging.' }
$jpackage = (Get-Command jpackage.exe -ErrorAction Stop).Source
$vendor = "Bj$([char]0x00f6)rn Kahlert"
$Destination = [IO.Path]::GetFullPath($Destination)
$imageDirectory = Join-Path $Destination "app-image-$AppVersion"
if (Test-Path -LiteralPath $imageDirectory) { throw "App-image destination already exists: $imageDirectory" }
if (Test-Path -LiteralPath (Join-Path $Destination "GameTier-$AppVersion.msi")) { throw 'Installer destination already exists.' }
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
$resources = Join-Path $Destination "resources-$AppVersion"
$javaDirectory = Split-Path (Split-Path $jpackage -Parent) -Parent
& (Join-Path $javaDirectory 'bin/jimage.exe') extract --include 'glob:**/jdk/jpackage/internal/resources/main.wxs' `
    --dir (Join-Path $resources 'jpackage') (Join-Path $javaDirectory 'lib/modules')
$template = Join-Path $resources 'jpackage/jdk.jpackage/jdk/jpackage/internal/resources/main.wxs'
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $template -PathType Leaf)) {
    throw "Cannot extract the installed JDK's jpackage WiX template."
}
$main = New-Object Xml.XmlDocument
$main.PreserveWhitespace = $true
$main.Load($template)
$namespaces = New-Object Xml.XmlNamespaceManager($main.NameTable)
$namespaces.AddNamespace('w', 'http://schemas.microsoft.com/wix/2006/wi')
if ($main.SelectNodes('/w:Wix/w:Product/w:Feature[@Id="DefaultFeature"]', $namespaces).Count -ne 1) {
    throw 'The installed JDK changed its WiX template; review Explorer installer integration.'
}
$feature = $main.SelectSingleNode('/w:Wix/w:Product/w:Feature[@Id="DefaultFeature"]', $namespaces)
$restartManagerShutdown = $main.CreateElement('Property', $main.DocumentElement.NamespaceURI)
$restartManagerShutdown.SetAttribute('Id', 'MSIRMSHUTDOWN')
$restartManagerShutdown.SetAttribute('Value', '1')
$feature.ParentNode.InsertBefore($restartManagerShutdown, $feature) | Out-Null
$reference = $main.CreateElement('ComponentGroupRef', $main.DocumentElement.NamespaceURI)
$reference.SetAttribute('Id', 'GameTierExplorer')
$feature.AppendChild($reference) | Out-Null
$integration = New-Object Xml.XmlDocument
$integration.Load((Join-Path $PSScriptRoot 'ExplorerIntegration.wxi'))
$fragment = $integration.DocumentElement.FirstChild
$main.DocumentElement.AppendChild($main.ImportNode($fragment, $true)) | Out-Null
$preservation = New-Object Xml.XmlDocument
$preservation.Load((Join-Path $PSScriptRoot 'PreserveSettings.wxi'))
$preservation.DocumentElement.SelectSingleNode('*/*[local-name()="Binary"]').SetAttribute('SourceFile', $SettingsLibrary)
$product = $feature.ParentNode
foreach ($element in $preservation.DocumentElement.FirstChild.ChildNodes) {
    if ($element.LocalName -eq 'InstallExecuteSequence') {
        $sequence = $product.SelectSingleNode('w:InstallExecuteSequence', $namespaces)
        foreach ($action in $element.ChildNodes) { $sequence.AppendChild($main.ImportNode($action, $true)) | Out-Null }
    } else { $product.AppendChild($main.ImportNode($element, $true)) | Out-Null }
}
$main.Save((Join-Path $resources 'main.wxs'))
Copy-Item -LiteralPath (Join-Path $projectDirectory 'LICENSE') -Destination $InputDirectory
Copy-Item -LiteralPath (Join-Path $projectDirectory 'desktopApp/packaging/explorer/third_party/nlohmann/LICENSE.MIT') `
    -Destination (Join-Path $InputDirectory 'GameTierExplorer.LICENSE.txt')

& $jpackage --type app-image --name GameTier --app-version $AppVersion `
    --vendor $vendor `
    --description 'Move games between storage tiers while preserving their original paths.' `
    --input $InputDirectory --main-jar GameTier.jar --main-class com.bkahlert.gametier.MainKt `
    --dest $imageDirectory --icon (Join-Path $projectDirectory 'desktopApp/packaging/GameTier.ico') `
    --java-options '--enable-native-access=ALL-UNNAMED'
if ($LASTEXITCODE -ne 0) { throw "jpackage app-image failed with exit code $LASTEXITCODE." }
$applicationImage = Join-Path $imageDirectory 'GameTier'
Copy-Item -LiteralPath $ExplorerLibrary -Destination (Join-Path $applicationImage 'GameTierExplorer.dll')

& $jpackage --type msi --name GameTier --app-version $AppVersion `
    --vendor $vendor `
    --description 'Move games between storage tiers while preserving their original paths.' `
    --app-image $applicationImage --dest $Destination `
    --icon (Join-Path $projectDirectory 'desktopApp/packaging/GameTier.ico') `
    --license-file (Join-Path $projectDirectory 'LICENSE') `
    --resource-dir $resources --win-per-user-install --win-menu `
    --win-upgrade-uuid 9db43bc2-4812-4649-a1a0-66489bdf19c0
if ($LASTEXITCODE -ne 0) { throw "jpackage MSI failed with exit code $LASTEXITCODE." }
$installer = Join-Path $Destination "GameTier-$AppVersion.msi"
& (Join-Path $PSScriptRoot 'Test-ExplorerInstallerPackage.ps1') -Installer $installer -ExplorerLibrary $ExplorerLibrary
Write-Output $installer
