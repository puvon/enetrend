Set-StrictMode -Version Latest

function Invoke-AdbCommand {
    param([string]$AdbPath, [string[]]$Arguments)
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & $AdbPath @Arguments 2>&1
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($code -ne 0) { throw "ADB failed ($code): $($output -join ' ')" }
    return (($output | ForEach-Object { $_.ToString() }) -join "`n").Trim()
}

function Assert-AndroidEmulator {
    param([string]$AdbPath, [string]$Serial)
    # Reject USB and network serials before issuing even a read command.
    if ($Serial -cnotmatch '^emulator-[0-9]+$') { throw 'An explicit emulator-NNNN serial is required. Physical devices are prohibited.' }
    if ((Invoke-AdbCommand $AdbPath @('-s', $Serial, 'get-state')) -ne 'device') { throw 'Emulator is not online.' }
    $kernel = Invoke-AdbCommand $AdbPath @('-s', $Serial, 'shell', 'getprop', 'ro.kernel.qemu')
    $boot = Invoke-AdbCommand $AdbPath @('-s', $Serial, 'shell', 'getprop', 'ro.boot.qemu')
    $hardware = Invoke-AdbCommand $AdbPath @('-s', $Serial, 'shell', 'getprop', 'ro.hardware')
    $model = Invoke-AdbCommand $AdbPath @('-s', $Serial, 'shell', 'getprop', 'ro.product.model')
    $fingerprint = Invoke-AdbCommand $AdbPath @('-s', $Serial, 'shell', 'getprop', 'ro.build.fingerprint')
    $sdk = Invoke-AdbCommand $AdbPath @('-s', $Serial, 'shell', 'getprop', 'ro.build.version.sdk')
    if (($kernel -ne '1' -and $boot -ne '1') -or $hardware -cnotin @('ranchu', 'goldfish') -or
        ($model -cnotlike 'sdk*' -and $model -cnotlike 'Android SDK built for*') -or
        ($fingerprint -cnotlike '*/sdk*' -and $fingerprint -cnotlike 'generic/*' -and $fingerprint -cnotlike 'generic_x86/*')) {
        throw 'Device is not a recognized standard Android SDK emulator. Refusing all mutations.'
    }
    if ($sdk -notmatch '^[0-9]+$' -or [int]$sdk -lt 34) { throw 'An API 34 or newer emulator is required.' }
    $avd = Invoke-AdbCommand $AdbPath @('-s', $Serial, 'emu', 'avd', 'name')
    $lines = @($avd -split '\r?\n' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($lines.Count -ne 2 -or $lines[1] -cne 'OK') { throw 'Android emulator console verification failed.' }
}

function Invoke-EmulatorMutation {
    param([string]$AdbPath, [string]$Serial, [string[]]$Arguments)
    Assert-AndroidEmulator $AdbPath $Serial
    Invoke-AdbCommand $AdbPath (@('-s', $Serial) + $Arguments)
}
