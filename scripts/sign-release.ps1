param(
    [string]$Version = '1.0.0',
    [switch]$Build
)

$ErrorActionPreference = 'Stop'
$releaseRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$releaseKeystore = Join-Path $releaseRoot 'signing/mindkit-release.jks'
$releasePasswordFile = Join-Path $releaseRoot 'signing/release-password.dpapi'
$releaseUnsigned = Join-Path $releaseRoot 'app/build/outputs/apk/release/app-release-unsigned.apk'
$releaseOutputDir = Join-Path $releaseRoot 'dist'
$releaseOutput = Join-Path $releaseOutputDir "MindKit-v$Version.apk"
$releaseSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { 'C:\Android\Sdk' }
$releaseTools = Join-Path $releaseSdk 'build-tools/36.0.0'
$releaseAapt = Join-Path $releaseTools 'aapt.exe'
$releaseZipalign = Join-Path $releaseTools 'zipalign.exe'
$releaseApksigner = Join-Path $releaseTools 'apksigner.bat'

foreach ($releaseRequired in @($releaseKeystore, $releasePasswordFile, $releaseAapt, $releaseZipalign, $releaseApksigner)) {
    if (-not (Test-Path -LiteralPath $releaseRequired)) { throw "Required release input is missing: $releaseRequired" }
}
if (Test-Path -LiteralPath $releaseOutput) { throw "Signed release already exists: $releaseOutput" }

if ($Build) {
    Push-Location $releaseRoot
    try {
        & (Join-Path $releaseRoot 'gradlew.bat') assembleRelease --no-parallel --max-workers=1 --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
    } finally { Pop-Location }
}
if (-not (Test-Path -LiteralPath $releaseUnsigned)) { throw "Unsigned release APK is missing: $releaseUnsigned" }

$releasePackage = (& $releaseAapt dump badging $releaseUnsigned | Select-String '^package:' | Select-Object -First 1).Line
if ($releasePackage -notmatch "name='com\.localai\.toolkit'\s+versionCode='\d+'\s+versionName='$([regex]::Escape($Version))'") {
    throw "Release APK package or version does not match MindKit v$Version."
}
& $releaseZipalign -c 4 $releaseUnsigned
if ([int]$LASTEXITCODE -ne 0) {
    throw 'Release APK ZIP alignment check failed.'
}

New-Item -ItemType Directory -Path $releaseOutputDir -Force | Out-Null
$releaseSecure = ConvertTo-SecureString ((Get-Content -LiteralPath $releasePasswordFile -Raw).Trim())
$releasePointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($releaseSecure)
try {
    $env:MINDKIT_SIGNING_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($releasePointer)
    & $releaseApksigner sign --ks $releaseKeystore --ks-key-alias mindkit `
        --ks-pass env:MINDKIT_SIGNING_PASSWORD --key-pass env:MINDKIT_SIGNING_PASSWORD `
        --out $releaseOutput $releaseUnsigned
    if ([int]$LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
} finally {
    Remove-Item Env:MINDKIT_SIGNING_PASSWORD -ErrorAction SilentlyContinue
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($releasePointer)
}

& $releaseApksigner verify --verbose --print-certs $releaseOutput
if ([int]$LASTEXITCODE -ne 0) { throw 'Signed APK verification failed.' }
Get-FileHash -Algorithm SHA256 -LiteralPath $releaseOutput | Select-Object Path, Hash
