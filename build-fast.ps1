param(
    [switch]$Install,
    [int]$Workers = [Math]::Min(12, [Environment]::ProcessorCount),
    [int]$ProxyPort = 7897,
    [string]$Abi = 'arm64-v8a'
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$javaHome = 'C:\Program Files\Android\Android Studio\jbr'
$androidHome = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$java = Join-Path $javaHome 'bin\java.exe'
$adb = Join-Path $androidHome 'platform-tools\adb.exe'
$wrapper = Join-Path $root 'gradle\wrapper\gradle-wrapper.jar'
$apk = Join-Path $root 'TMessagesProj_AppStandalone\build\outputs\apk\afat\fast\app.apk'
$intermediateApk = Join-Path $root 'TMessagesProj_AppStandalone\build\intermediates\apk\afat\fast\app.apk'

if (-not (Test-Path -LiteralPath $java)) {
    throw "Java runtime not found: $java"
}
if (-not (Test-Path -LiteralPath $wrapper)) {
    throw "Gradle wrapper not found: $wrapper"
}

$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $androidHome
$env:ANDROID_SDK_ROOT = $androidHome

$javaArgs = @()
if (Test-NetConnection 127.0.0.1 -Port $ProxyPort -InformationLevel Quiet -WarningAction SilentlyContinue) {
    $javaArgs += "-Dhttp.proxyHost=127.0.0.1"
    $javaArgs += "-Dhttp.proxyPort=$ProxyPort"
    $javaArgs += "-Dhttps.proxyHost=127.0.0.1"
    $javaArgs += "-Dhttps.proxyPort=$ProxyPort"
}

$javaArgs += @(
    '-classpath',
    $wrapper,
    'org.gradle.wrapper.GradleWrapperMain',
    ':TMessagesProj_AppStandalone:assembleAfatFast',
    '--parallel',
    "--max-workers=$Workers",
    "-Pandroid.injected.build.abi=$Abi",
    '--build-cache',
    '--configure-on-demand',
    '--console=plain'
)

$timer = [Diagnostics.Stopwatch]::StartNew()
Push-Location $root
try {
    & $java @javaArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
    $timer.Stop()
}

if (-not (Test-Path -LiteralPath $apk) -or ((Test-Path -LiteralPath $intermediateApk) -and ((Get-Item -LiteralPath $intermediateApk).LastWriteTime -gt (Get-Item -LiteralPath $apk).LastWriteTime))) {
    if (Test-Path -LiteralPath $intermediateApk) {
        $apkDir = Split-Path -Parent $apk
        if (-not (Test-Path -LiteralPath $apkDir)) {
            New-Item -ItemType Directory -Force -Path $apkDir | Out-Null
        }
        Copy-Item -LiteralPath $intermediateApk -Destination $apk -Force
    } else {
        throw "APK output not found: $apk"
    }
}

Write-Host ("Fast build completed in {0:mm\:ss}: {1}" -f $timer.Elapsed, $apk)

if ($Install) {
    if (-not (Test-Path -LiteralPath $adb)) {
        throw "ADB not found: $adb"
    }
    & $adb install -r -t $apk
    if ($LASTEXITCODE -ne 0) {
        throw "ADB install failed with exit code $LASTEXITCODE"
    }
}
