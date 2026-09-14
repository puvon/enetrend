$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'EmulatorSafety.ps1')

# No ADB binary, device or emulator is accessed by these tests.
$script:responses = @{}
$script:commands = [Collections.Generic.List[string]]::new()
function Reset-Evidence {
    $script:commands.Clear()
    $script:responses = @{
        'get-state' = 'device'; 'shell getprop ro.kernel.qemu' = '1'; 'shell getprop ro.boot.qemu' = '1'
        'shell getprop ro.hardware' = 'ranchu'; 'shell getprop ro.product.model' = 'sdk_gphone64_x86_64'
        'shell getprop ro.build.fingerprint' = 'google/sdk_gphone64_x86_64/emu64xa:14'
        'shell getprop ro.build.version.sdk' = '34'; 'emu avd name' = "TestAvd`nOK"
    }
}
function Invoke-AdbCommand {
    param([string]$AdbPath, [string[]]$Arguments)
    if ($Arguments[0] -ne '-s' -or $Arguments[1] -ne 'emulator-5554') { throw 'Missing explicit target' }
    $command = $Arguments[2..($Arguments.Count - 1)] -join ' '
    $script:commands.Add($command)
    if ($script:responses.ContainsKey($command)) { return $script:responses[$command] }
    if ($command -eq 'install -r test.apk') { return 'Success' }
    throw "Unexpected command: $command"
}
function Assert-Blocked {
    param([string]$Serial = 'emulator-5554')
    $blocked = $false
    try { Invoke-EmulatorMutation 'unused' $Serial @('install', '-r', 'test.apk') | Out-Null } catch { $blocked = $true }
    if (-not $blocked -or $script:commands.Contains('install -r test.apk')) { throw 'Unsafe device was not blocked before installation.' }
}

foreach ($serial in @('', 'R58M1234ABC', '192.168.1.2:5555', 'emulator-5554;anything')) {
    Reset-Evidence
    Assert-Blocked $serial
    if ($script:commands.Count -ne 0) { throw 'Invalid serial caused ADB access.' }
}
foreach ($case in @(
    @('get-state', 'offline'), @('shell getprop ro.hardware', 'qcom'),
    @('shell getprop ro.product.model', 'Pixel 8'), @('shell getprop ro.build.fingerprint', 'unknown'),
    @('shell getprop ro.build.version.sdk', '33'), @('shell getprop ro.build.version.sdk', ''),
    @('emu avd name', 'unknown')
)) {
    Reset-Evidence
    $script:responses[$case[0]] = $case[1]
    Assert-Blocked
}
Reset-Evidence
$script:responses['shell getprop ro.kernel.qemu'] = ''
$script:responses['shell getprop ro.boot.qemu'] = ''
Assert-Blocked
Reset-Evidence
Invoke-EmulatorMutation 'unused' 'emulator-5554' @('install', '-r', 'test.apk') | Out-Null
if (-not $script:commands.Contains('install -r test.apk')) { throw 'Valid AVD was rejected.' }
# Re-check evidence on every mutation, rather than trusting the initial verification.
$script:commands.Clear()
$script:responses['shell getprop ro.hardware'] = 'qcom'
Assert-Blocked
Write-Output 'Passed: 14 emulator safety scenarios; no device access.'
