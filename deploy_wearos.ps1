<#
.SYNOPSIS
    Deploys WearBridge to a physical Wear OS watch via Wi-Fi or USB ADB.

.DESCRIPTION
    Builds (optional) and deploys the Wear OS APK to a physical Galaxy Watch / Pixel Watch,
    auto-grants all runtime permissions via `adb install -r -g`, and launches MainActivity.

.PARAMETER WatchAddress
    Optional IP address and port of the target watch (e.g. "192.168.1.100:5555").
    If omitted, auto-detects the connected device via `adb devices`.

.PARAMETER BuildFirst
    If specified, builds the debug APK using Gradle wrapper before installing.

.EXAMPLE
    .\deploy_wearos.ps1 -WatchAddress 192.168.1.42:5555
    .\deploy_wearos.ps1 -BuildFirst
#>

param(
    [Parameter(Position=0)]
    [string]$WatchAddress,

    [switch]$BuildFirst
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$WearOsDir = Join-Path $ScriptDir "wearos-app"

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "   WearBridge Wear OS Deployment Utility   " -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# 1. Resolve Android SDK and ADB path
$AdbExe = $null

# Check PATH
$AdbInPath = Get-Command adb -ErrorAction SilentlyContinue
if ($AdbInPath) {
    $AdbExe = $AdbInPath.Source
    Write-Host "[+] Found adb in PATH: $AdbExe" -ForegroundColor Green
}

# Check local.properties in wearos-app
if (-not $AdbExe) {
    $LocalPropsPath = Join-Path $WearOsDir "local.properties"
    if (Test-Path $LocalPropsPath) {
        Get-Content $LocalPropsPath | ForEach-Object {
            if ($_ -match "^\s*sdk\.dir\s*=\s*(.+)$") {
                $SdkDir = $Matches[1].Replace('\\', '\').Trim()
                $Candidate = Join-Path $SdkDir "platform-tools\adb.exe"
                if (Test-Path $Candidate) {
                    $AdbExe = $Candidate
                    Write-Host "[+] Found adb from local.properties: $AdbExe" -ForegroundColor Green
                }
            }
        }
    }
}

# Check ANDROID_HOME / ANDROID_SDK_ROOT environment variables
if (-not $AdbExe) {
    $EnvSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    if ($EnvSdk -and (Test-Path (Join-Path $EnvSdk "platform-tools\adb.exe"))) {
        $AdbExe = Join-Path $EnvSdk "platform-tools\adb.exe"
        Write-Host "[+] Found adb from environment variable: $AdbExe" -ForegroundColor Green
    }
}

# Check standard Windows AppData location
if (-not $AdbExe) {
    $DefaultSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $DefaultSdk) {
        $AdbExe = $DefaultSdk
        Write-Host "[+] Found adb in standard AppData path: $AdbExe" -ForegroundColor Green
    }
}

if (-not $AdbExe) {
    Write-Error "ERROR: Unable to locate adb.exe. Please install Android SDK Platform-Tools or set ANDROID_HOME."
}

# 2. Connect / Resolve Target Device
$TargetDevice = $null

if ($WatchAddress) {
    Write-Host "[*] Connecting to watch at $WatchAddress..." -ForegroundColor Yellow
    & $AdbExe connect $WatchAddress
    $TargetDevice = $WatchAddress
} else {
    Write-Host "[*] Auto-detecting connected ADB devices..." -ForegroundColor Yellow
    $DevicesOutput = & $AdbExe devices | Select-Object -Skip 1
    $ActiveDevices = @()
    foreach ($line in $DevicesOutput) {
        if ($line -match "^([^\s]+)\s+device$") {
            $ActiveDevices += $Matches[1]
        }
    }

    if ($ActiveDevices.Count -eq 0) {
        Write-Error "No connected ADB devices found. Enable Wireless Debugging on your watch and specify -WatchAddress <IP:PORT>."
    } elseif ($ActiveDevices.Count -eq 1) {
        $TargetDevice = $ActiveDevices[0]
        Write-Host "[+] Using connected device: $TargetDevice" -ForegroundColor Green
    } else {
        Write-Host "[!] Multiple devices connected:" -ForegroundColor Yellow
        for ($i = 0; $i -lt $ActiveDevices.Count; $i++) {
            Write-Host "  [$i] $($ActiveDevices[$i])"
        }
        $TargetDevice = $ActiveDevices[0]
        Write-Host "[+] Defaulting to device [0]: $TargetDevice" -ForegroundColor Green
    }
}

# 3. Build APK if requested or if APK does not exist
$ApkPath = Join-Path $WearOsDir "app\build\outputs\apk\debug\app-debug.apk"

if ($BuildFirst -or (-not (Test-Path $ApkPath))) {
    Write-Host "[*] Building Wear OS debug APK with Gradle..." -ForegroundColor Yellow
    Push-Location $WearOsDir
    try {
        if ($IsWindows -or ($PSVersionTable.PSVersion.Major -le 5)) {
            .\gradlew.bat assembleDebug
        } else {
            ./gradlew assembleDebug
        }
        if ($LASTEXITCODE -ne 0) {
            Write-Error "Gradle build failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path $ApkPath)) {
    Write-Error "APK not found at $ApkPath. Please run with -BuildFirst."
}

Write-Host "[+] Located APK: $ApkPath" -ForegroundColor Green

# 4. Install APK with -r (reinstall) and -g (auto-grant all runtime permissions)
Write-Host "[*] Installing APK to $TargetDevice with auto-grant permissions (-g)..." -ForegroundColor Yellow
$InstallOutput = & $AdbExe -s $TargetDevice install -r -g $ApkPath
Write-Host $InstallOutput

if ($InstallOutput -notmatch "Success") {
    Write-Error "APK installation failed on $TargetDevice"
}
Write-Host "[+] Installation succeeded with all runtime permissions granted!" -ForegroundColor Green

# 5. Launch MainActivity
Write-Host "[*] Launching WearBridge MainActivity..." -ForegroundColor Yellow
& $AdbExe -s $TargetDevice shell am start -n com.wearos.ancsbridge/.ui.MainActivity

Write-Host "==========================================" -ForegroundColor Green
Write-Host "   Deployment Complete! WearBridge is live. " -ForegroundColor Green
Write-Host "==========================================" -ForegroundColor Green
