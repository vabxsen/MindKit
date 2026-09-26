$ErrorActionPreference = 'Stop'
$releaseRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$releasePasswordFile = Join-Path $releaseRoot 'signing/release-password.dpapi'
if (-not (Test-Path -LiteralPath $releasePasswordFile)) {
    throw "Release password file is missing: $releasePasswordFile"
}
$releaseSecure = ConvertTo-SecureString ((Get-Content -LiteralPath $releasePasswordFile -Raw).Trim())
$releasePointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($releaseSecure)
try {
    [Runtime.InteropServices.Marshal]::PtrToStringBSTR($releasePointer) | Set-Clipboard
    Write-Output 'Release password copied to the clipboard. Store it in a password manager, then clear the clipboard.'
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($releasePointer)
}
