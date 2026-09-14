[CmdletBinding()]
param(
    [string]$Serial,
    [ValidateSet('Inspect', 'Seed')][string]$Mode = 'Inspect',
    [ValidatePattern('^\d{4}-\d{2}-\d{2}$')][string]$EndDate,
    [string]$SdkPath,
    [string]$JavaHome,
    [switch]$BuildOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'EmulatorSafety.ps1')

function Test-Jdk {
    param([string]$Path)
    if (-not $Path -or -not (Test-Path -LiteralPath (Join-Path $Path 'bin/javac.exe'))) { return $false }
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = Join-Path $Path 'bin/java.exe'
    $info.Arguments = '-version'
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardError = $true
    $info.RedirectStandardOutput = $true
    $process = [Diagnostics.Process]::Start($info)
    try {
        if (-not $process.WaitForExit(10000)) { $process.Kill(); return $false }
        $version = $process.StandardError.ReadToEnd() + $process.StandardOutput.ReadToEnd()
        return $process.ExitCode -eq 0 -and $version -match 'version "([0-9]+)' -and [int]$Matches[1] -ge 17
    } finally { $process.Dispose() }
}

$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $SdkPath) {
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android/Sdk'))) {
        if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'platform-tools/adb.exe'))) { $SdkPath = $candidate; break }
    }
}
if (-not $SdkPath -or -not (Test-Path -LiteralPath (Join-Path $SdkPath 'platform-tools/adb.exe'))) {
    throw 'Android SDK not found. Supply -SdkPath.'
}
$SdkPath = (Resolve-Path -LiteralPath $SdkPath).Path
$adb = Join-Path $SdkPath 'platform-tools/adb.exe'
if (-not $BuildOnly) { Assert-AndroidEmulator $adb $Serial }
if ($EndDate) { [void][datetime]::ParseExact($EndDate, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture) }

if ($JavaHome) {
    if (-not (Test-Jdk $JavaHome)) { throw '-JavaHome must identify a working JDK 17 or newer.' }
} else {
    $cache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
    $candidates = @($env:JAVA_HOME, (Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'))
    $jdks = Join-Path $cache 'jdks'
    if (Test-Path -LiteralPath $jdks) { $candidates += @(Get-ChildItem -LiteralPath $jdks -Directory | Select-Object -ExpandProperty FullName) }
    foreach ($candidate in $candidates) { if (Test-Jdk $candidate) { $JavaHome = $candidate; break } }
    if (-not $JavaHome) { throw 'JDK 17 or newer not found. Supply -JavaHome (the project uses JDK 25).' }
}

# Only ignored machine-local configuration is generated. Never edit the app's Gradle project.
$utf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'local.properties'), "sdk.dir=$($SdkPath.Replace('\', '/'))`n", $utf8)
$oldJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JavaHome
    & (Join-Path $repository 'gradlew.bat') -p $PSScriptRoot test lint assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Helper build/checks failed ($LASTEXITCODE)." }
} finally { $env:JAVA_HOME = $oldJava }
if ($BuildOnly) { Write-Output 'Build and checks passed. No device was accessed.'; return }

$package = 'io.github.puvon.enetrend.demodata'
$apk = Join-Path $PSScriptRoot 'build/outputs/apk/debug/enetrend-demo-data-helper-debug.apk'
Invoke-EmulatorMutation $adb $Serial @('install', '-r', $apk) | Write-Output
$permissions = @('READ_NUTRITION', 'READ_TOTAL_CALORIES_BURNED', 'READ_WEIGHT')
if ($Mode -eq 'Seed') { $permissions += @('WRITE_NUTRITION', 'WRITE_TOTAL_CALORIES_BURNED', 'WRITE_WEIGHT', 'WRITE_BASAL_METABOLIC_RATE') }
foreach ($permission in $permissions) {
    Invoke-EmulatorMutation $adb $Serial @('shell', 'pm', 'grant', $package, "android.permission.health.$permission") | Out-Null
}
$request = [guid]::NewGuid().ToString('N')
Invoke-EmulatorMutation $adb $Serial @('shell', 'am', 'force-stop', $package) | Out-Null
$launch = @('shell', 'am', 'start', '-W', '-n', "$package/.MainActivity", '--es', 'mode', $Mode.ToLowerInvariant(), '--es', 'requestId', $request)
if ($EndDate) { $launch += @('--es', 'endDate', $EndDate) }
Invoke-EmulatorMutation $adb $Serial $launch | Write-Output

$timer = [Diagnostics.Stopwatch]::StartNew()
Write-Output 'Waiting for the emulator-only helper result...'
while ($timer.Elapsed.TotalSeconds -lt 60) {
    Assert-AndroidEmulator $adb $Serial
    $ready = Invoke-AdbCommand $adb @('-s', $Serial, 'shell', 'run-as', $package, 'ls', 'files')
    if (($ready -split '\r?\n') -contains "result-$request.txt") {
        $result = Invoke-AdbCommand $adb @('-s', $Serial, 'shell', 'run-as', $package, 'cat', "files/result-$request.txt")
        if (-not $result.StartsWith('OK ')) { throw $result }
        Write-Output $result
        return
    }
    Start-Sleep -Milliseconds 500
}
throw 'Helper result timed out. No success is assumed; inspect the emulator screen.'
