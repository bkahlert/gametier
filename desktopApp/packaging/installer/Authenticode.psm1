Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not ('GameTier.Packaging.Authenticode' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

namespace GameTier.Packaging
{
    public static class Authenticode
    {
        [StructLayout(LayoutKind.Sequential)]
        private struct WinTrustFileInfo
        {
            public uint Size;
            public IntPtr FilePath;
            public IntPtr File;
            public IntPtr KnownSubject;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct WinTrustData
        {
            public uint Size;
            public IntPtr PolicyCallbackData;
            public IntPtr SipClientData;
            public uint UiChoice;
            public uint RevocationChecks;
            public uint UnionChoice;
            public IntPtr FileInfo;
            public uint StateAction;
            public IntPtr StateData;
            public IntPtr UrlReference;
            public uint ProviderFlags;
            public uint UiContext;
            public IntPtr SignatureSettings;
        }

        [DllImport("wintrust.dll", ExactSpelling = true)]
        private static extern uint WinVerifyTrust(IntPtr window, ref Guid action, ref WinTrustData data);

        public static uint Verify(string path)
        {
            IntPtr pathPointer = IntPtr.Zero;
            IntPtr filePointer = IntPtr.Zero;
            try
            {
                pathPointer = Marshal.StringToHGlobalUni(path);
                var file = new WinTrustFileInfo
                {
                    Size = (uint)Marshal.SizeOf(typeof(WinTrustFileInfo)),
                    FilePath = pathPointer
                };
                filePointer = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(WinTrustFileInfo)));
                Marshal.StructureToPtr(file, filePointer, false);
                var data = new WinTrustData
                {
                    Size = (uint)Marshal.SizeOf(typeof(WinTrustData)),
                    UiChoice = 2, // WTD_UI_NONE
                    RevocationChecks = 0, // WTD_REVOKE_NONE
                    UnionChoice = 1, // WTD_CHOICE_FILE
                    FileInfo = filePointer,
                    StateAction = 1, // WTD_STATEACTION_VERIFY
                    ProviderFlags = 0x1010 // WTD_CACHE_ONLY_URL_RETRIEVAL | WTD_REVOCATION_CHECK_NONE
                };
                var action = new Guid("00AAC56B-CD44-11D0-8CC2-00C04FC295EE");
                try
                {
                    return WinVerifyTrust(new IntPtr(-1), ref action, ref data);
                }
                finally
                {
                    data.StateAction = 2; // WTD_STATEACTION_CLOSE
                    WinVerifyTrust(new IntPtr(-1), ref action, ref data);
                }
            }
            finally
            {
                if (filePointer != IntPtr.Zero) Marshal.FreeHGlobal(filePointer);
                if (pathPointer != IntPtr.Zero) Marshal.FreeHGlobal(pathPointer);
            }
        }
    }
}
'@
}

function Assert-AuthenticodeSignature {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string] $Path,
        [Parameter(Mandatory)][string] $Thumbprint
    )

    $resolvedPath = (Resolve-Path -LiteralPath $Path).ProviderPath
    $status = [GameTier.Packaging.Authenticode]::Verify($resolvedPath)
    if ($status -ne 0 -and $status -ne [uint32]::Parse('800B0109', [Globalization.NumberStyles]::HexNumber)) {
        throw ("Invalid Authenticode signature on '$Path': WinVerifyTrust returned 0x{0:X8}." -f $status)
    }
    $signature = Get-AuthenticodeSignature -LiteralPath $resolvedPath
    if ($null -eq $signature.SignerCertificate -or $signature.SignerCertificate.Thumbprint -ine $Thumbprint) {
        throw "Signing certificate does not match the expected thumbprint for '$Path'."
    }
}

Export-ModuleMember -Function Assert-AuthenticodeSignature
