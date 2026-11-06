param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Setup', 'Cleanup')]
    [string] $Action,
    [Parameter(Mandatory = $true)]
    [string] $FixtureDirectory,
    [Parameter(Mandatory = $true)]
    [string] $UserSid
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-FixtureDirectory {
    $directory = Get-Item -LiteralPath $FixtureDirectory
    if (-not $directory.PSIsContainer -or
        $directory.Name -notmatch '^gametier-volumes-[0-9a-f]{32}$') {
        throw 'Refusing an unrelated fixture directory.'
    }
    for ($parent = $directory; $null -ne $parent; $parent = $parent.Parent) {
        if (($parent.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'Refusing a fixture directory reached through a reparse point.'
        }
    }
    return $directory.FullName
}

function Invoke-VolumeSetup {
    $roots = @()
    $owned = @()
    $ownershipFile = Join-Path $FixtureDirectory 'ownership.json'
    if (Test-Path -LiteralPath $ownershipFile) { throw 'Fixture ownership record already exists.' }
    foreach ($name in @('ntfs-1', 'ntfs-2', 'refs-1', 'refs-2')) {
        $image = Join-Path $FixtureDirectory "$name.vhdx"
        if (Test-Path -LiteralPath $image) { throw "Fixture image already exists: $image" }
    }
    foreach ($name in @('ntfs-1', 'ntfs-2', 'refs-1', 'refs-2')) {
        $image = Join-Path $FixtureDirectory "$name.vhdx"
        $commands = Join-Path $FixtureDirectory "$name.diskpart"
        try {
            @(
                "create vdisk file=`"$image`" maximum=65536 type=expandable"
                'exit'
            ) | Set-Content -LiteralPath $commands -Encoding ASCII
            $output = & "$env:SystemRoot\System32\diskpart.exe" /s $commands 2>&1
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $image)) {
                throw "Could not create fixture image: $output"
            }
            $owned += @{
                Name = "$name.vhdx"
                CreationTicks = (Get-Item -LiteralPath $image).CreationTimeUtc.Ticks.ToString()
            }
            ConvertTo-Json -InputObject $owned | Set-Content -LiteralPath $ownershipFile
        } finally {
            if (Test-Path -LiteralPath $commands) { Remove-Item -LiteralPath $commands }
        }

        $mounted = Mount-DiskImage -ImagePath $image -NoDriveLetter -PassThru
        $disk = $mounted | Get-Disk
        if ($disk.PartitionStyle -ne 'RAW' -or $disk.BusType -ne 'File Backed Virtual') {
            throw "Refusing to format a disk that is not the new RAW fixture image: $image"
        }
        $partition = $disk | Initialize-Disk -PartitionStyle GPT -PassThru |
            New-Partition -AssignDriveLetter -UseMaximumSize
        $fileSystem = if ($name.StartsWith('ntfs')) { 'NTFS' } else { 'ReFS' }
        $format = @{
            Partition = $partition
            FileSystem = $fileSystem
            NewFileSystemLabel = "gametier-$name"
            Confirm = $false
            Force = $true
        }
        if ($fileSystem -eq 'ReFS' -and (Get-Command Format-Volume).Parameters.ContainsKey('DevDrive')) {
            $format.DevDrive = $true
        }
        $volume = Format-Volume @format
        if ($volume.FileSystem -ne $fileSystem) { throw "Unexpected filesystem on fixture $image" }
        $root = "$($partition.DriveLetter):\gametier-tests"
        New-Item -Path $root -ItemType Directory | Out-Null
        $output = & "$env:SystemRoot\System32\icacls.exe" $root /grant "*${UserSid}:(OI)(CI)M" 2>&1
        if ($LASTEXITCODE -ne 0) { throw "Could not grant the test user access to fixture: $output" }
        $roots += $root
    }
    ConvertTo-Json -InputObject $roots | Set-Content -LiteralPath (Join-Path $FixtureDirectory 'roots.json')
}

function Invoke-VolumeCleanup {
    $failures = @()
    foreach ($name in @('ntfs-1', 'ntfs-2', 'refs-1', 'refs-2')) {
        $image = Join-Path $FixtureDirectory "$name.vhdx"
        if (-not (Test-Path -LiteralPath $image)) { continue }
        try {
            $file = Get-Item -LiteralPath $image
            if ($file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Refusing a non-regular fixture image: $image"
            }
            $record = @($ownership | Where-Object { $_.Name -eq "$name.vhdx" })
            if ($record.Count -ne 1 -or $record[0].CreationTicks -ne $file.CreationTimeUtc.Ticks.ToString()) {
                throw "Fixture ownership does not match image: $image"
            }
            $diskImage = Get-DiskImage -ImagePath $image
            if ($diskImage.Attached) { Dismount-DiskImage -ImagePath $image }
            if ((Get-DiskImage -ImagePath $image).Attached) { throw "Fixture is still attached: $image" }
            Remove-Item -LiteralPath $image
        } catch {
            $failures += "$image`: $($_.Exception.Message)"
        }
    }
    if ($failures.Count -gt 0) { throw ($failures -join [Environment]::NewLine) }
}

try {
    $FixtureDirectory = Assert-FixtureDirectory
    if ($Action -eq 'Cleanup') {
        $ownershipFile = Join-Path $FixtureDirectory 'ownership.json'
        if (-not (Test-Path -LiteralPath $ownershipFile)) { throw 'Missing fixture ownership record.' }
        $ownership = Get-Content -LiteralPath $ownershipFile -Raw | ConvertFrom-Json
    }
    $null = New-Object Security.Principal.SecurityIdentifier($UserSid)
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Fixture setup and cleanup require an elevated helper.'
    }
    if ($Action -eq 'Setup') { Invoke-VolumeSetup } else { Invoke-VolumeCleanup }
    exit 0
} catch {
    Write-Error -ErrorRecord $_ -ErrorAction Continue
    exit 1
}
