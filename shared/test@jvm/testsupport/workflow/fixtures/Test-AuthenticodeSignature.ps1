[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $Module,
    [Parameter(Mandatory)][string] $Directory,
    [Parameter(Mandatory)][ValidateSet('Signed', 'Tampered', 'DifferentCertificate', 'Unsigned')][string] $Scenario
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module $Module -Force
$certificates = @()
try {
    $certificate = New-SelfSignedCertificate -Type CodeSigningCert -Subject "CN=GameTier Authenticode Test $([guid]::NewGuid())" `
        -CertStoreLocation Cert:\CurrentUser\My -KeyAlgorithm RSA -KeyLength 2048 -HashAlgorithm SHA256
    $certificates += $certificate
    $path = Join-Path $Directory 'signed payload.ps1'
    [IO.File]::WriteAllText($path, "Write-Output 'original'`r`n", [Text.Encoding]::Unicode)
    $thumbprint = $certificate.Thumbprint
    if ($Scenario -ne 'Unsigned') {
        $signed = Set-AuthenticodeSignature -LiteralPath $path -Certificate $certificate -HashAlgorithm SHA256
        if ($null -eq $signed.SignerCertificate) { throw 'Fixture signing failed.' }
    }
    if ($Scenario -eq 'Tampered') {
        [IO.File]::WriteAllText($path, [IO.File]::ReadAllText($path).Replace("'original'", "'modified'"), [Text.Encoding]::Unicode)
    }
    if ($Scenario -eq 'DifferentCertificate') {
        $other = New-SelfSignedCertificate -Type CodeSigningCert -Subject "CN=GameTier Authenticode Test $([guid]::NewGuid())" `
            -CertStoreLocation Cert:\CurrentUser\My -KeyAlgorithm RSA -KeyLength 2048 -HashAlgorithm SHA256
        $certificates += $other
        $thumbprint = $other.Thumbprint
    }

    Assert-AuthenticodeSignature -Path $path -Thumbprint $thumbprint
    Write-Output 'Signature accepted'
} finally {
    foreach ($certificate in $certificates) {
        Remove-Item -LiteralPath "Cert:\CurrentUser\My\$($certificate.Thumbprint)" -DeleteKey
    }
}
