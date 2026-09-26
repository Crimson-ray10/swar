param([switch]$Install, [string]$Device)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$taskAndroid = 'C:\Program Files\Unity\Hub\Editor\6000.5.2f1\Editor\Data\PlaybackEngines\AndroidPlayer'
$taskJava = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { $env:JAVA_HOME } else { Join-Path $taskAndroid 'OpenJDK' }
$taskSdk = if ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) { $env:ANDROID_HOME } else { Join-Path $taskAndroid 'SDK' }
if (!(Test-Path "$taskJava\bin\java.exe")) { throw 'Set JAVA_HOME to a JDK 17+ installation.' }
if (!(Test-Path "$taskSdk\platforms\android-36")) { throw 'Set ANDROID_HOME to an SDK with platform android-36.' }
$env:JAVA_HOME = $taskJava
$env:ANDROID_HOME = $taskSdk
$env:ANDROID_SDK_ROOT = $taskSdk
& python (Join-Path $PSScriptRoot 'prepare-offline-runtime.py')
if ($LASTEXITCODE -ne 0) { throw 'Pinned runtime preparation failed.' }
Push-Location (Join-Path $projectRoot 'mobile')
try {
    $taskTargets = @('--console=plain', ':offline:testDebugUnitTest', ':offline:lintDebug', ':offline:assembleDebug')
    if (Test-Path "$taskAndroid\Tools\gradle\lib") {
        & "$taskJava\bin\java.exe" -classpath "$taskAndroid\Tools\gradle\lib\*" org.gradle.launcher.GradleMain @taskTargets
    } elseif (Get-Command gradle -ErrorAction SilentlyContinue) {
        & gradle @taskTargets
    } else { throw 'Install Gradle 9.1 or use the existing Unity Android toolchain.' }
    if ($LASTEXITCODE -ne 0) { throw 'Offline Android build/checks failed.' }
    $taskApk = Join-Path $projectRoot 'mobile\offline\build\outputs\apk\debug\offline-debug.apk'
    Write-Output "APK: $taskApk"
    if ($Install) {
        if ($Device) { & "$taskSdk\platform-tools\adb.exe" -s $Device install -r $taskApk }
        else { & "$taskSdk\platform-tools\adb.exe" install -r $taskApk }
        if ($LASTEXITCODE -ne 0) { throw 'Installation failed. Connect and authorize a phone.' }
    }
} finally { Pop-Location }
