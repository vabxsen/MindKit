param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Adb = 'C:\Android\Sdk\platform-tools\adb.exe'
)
$ErrorActionPreference = 'Stop'
# Run only on a disposable emulator: this replaces the debug app's current task.
if ($Serial -notmatch '^emulator-\d+$') { throw 'Use a disposable emulator, not a physical phone.' }
$package = 'com.localai.toolkit.debug'
$component = "$package/com.localai.toolkit.MainActivity"
$dumpPath = '/data/local/tmp/mindkit-process-verification.xml'
function Invoke-Adb([string[]]$Arguments) {
    $result = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($result -join "`n") }
    return ($result -join "`n")
}
function Read-Ui {
    # UiAutomator can report a first-launch idle timeout with exit code zero.
    # Never read a missing/stale dump or misclassify that as an app assertion.
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        $dump = Invoke-Adb @('shell', 'uiautomator', 'dump', $dumpPath)
        if ($dump.Contains('dumped to:')) {
            return Invoke-Adb @('shell', 'cat', $dumpPath)
        }
    }
    throw "Could not obtain a stable UI hierarchy: $dump"
}
function Wait-Text([string]$Text) {
    $deadline = [DateTime]::UtcNow.AddSeconds(25)
    do {
        if ((Read-Ui).Contains($Text)) { return }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Visible text missing after restoration: $Text"
}
function Restart-BackgroundProcess {
    $previousPid = Invoke-Adb @('shell', 'pidof', $package)
    $null = Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    # Waiting for a stable launcher hierarchy lets Android save the stopped activity.
    $null = Read-Ui
    $null = Invoke-Adb @('shell', 'am', 'kill', $package)
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    do {
        $remainingPid = & $Adb -s $Serial shell pidof $package
        if (-not $remainingPid) { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($remainingPid) { throw 'Background process did not terminate; no restoration claim can be made.' }
    $null = Invoke-Adb @('shell', 'am', 'start', '-W', '-n', $component,
        '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', '-f', '0x10200000')
    $currentPid = Invoke-Adb @('shell', 'pidof', $package)
    if ($currentPid -eq $previousPid) { throw 'Expected a new OS process.' }
    Write-Output "Process restarted: $previousPid -> $currentPid"
}
try {
    $null = Invoke-Adb @('shell', 'am', 'start', '-W', '-n', $component,
        '-a', 'android.intent.action.SEND', '-t', 'text/plain',
        '--es', 'android.intent.extra.TEXT', 'MindKitFirstPendingShare', '-f', '0x10008000')
    Wait-Text 'MindKitFirstPendingShare'
    $null = Invoke-Adb @('shell', 'am', 'start', '-W', '-n', $component,
        '-a', 'android.intent.action.SEND', '-t', 'text/plain',
        '--es', 'android.intent.extra.TEXT', 'MindKitLatestPendingShare', '-f', '0x10000000')
    Wait-Text 'MindKitLatestPendingShare'
    Restart-BackgroundProcess
    Wait-Text 'MindKitLatestPendingShare'
    if ((Read-Ui).Contains('MindKitFirstPendingShare')) { throw 'The obsolete share returned alongside the latest one.' }
    $null = Invoke-Adb @('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    $null = Read-Ui
    Restart-BackgroundProcess
    $restored = Read-Ui
    if (-not $restored.Contains('package="com.localai.toolkit.debug"')) {
        throw 'MindKit did not render after the second process restart.'
    }
    if ($restored.Contains('MindKitLatestPendingShare') -or $restored.Contains('MindKitFirstPendingShare')) {
        throw 'A dismissed share replayed after process death.'
    }
    Write-Output 'PASS: latest pending share survives real OS process death; dismissed share is not replayed.'
} finally {
    # Only remove this script's hierarchy dump, never app/user data.
    & $Adb -s $Serial shell rm -f $dumpPath | Out-Null
}
