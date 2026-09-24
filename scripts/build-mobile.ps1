param([switch]$Install, [string]$Device, [switch]$AcousticTest)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$unityAndroid = if (Test-Path 'C:\Program Files\Unity\Hub\Editor\6000.6.0f1\Editor\Data\PlaybackEngines\AndroidPlayer') {
    'C:\Program Files\Unity\Hub\Editor\6000.6.0f1\Editor\Data\PlaybackEngines\AndroidPlayer'
} else {
    'C:\Program Files\Unity\Hub\Editor\6000.5.2f1\Editor\Data\PlaybackEngines\AndroidPlayer'
}
$taskJava = Join-Path $unityAndroid 'OpenJDK'
$taskSdk = Join-Path $unityAndroid 'SDK'
$taskGradle = Join-Path $unityAndroid 'Tools\gradle\lib\*'
if (!(Test-Path "$taskJava\bin\java.exe")) { throw 'Unity Android JDK not found. Configure the paths in this script or build mobile/ using Android Studio.' }
$env:JAVA_HOME = $taskJava
$env:ANDROID_HOME = $taskSdk
$env:ANDROID_SDK_ROOT = $taskSdk
Push-Location (Join-Path $projectRoot 'mobile')
try {
    $taskArguments = @('--console=plain', ':app:assembleDebug')
    if ($AcousticTest) { $taskArguments += '-PacousticTest=true' }
    & "$taskJava\bin\java.exe" -classpath $taskGradle org.gradle.launcher.GradleMain @taskArguments
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
    $apk = Join-Path $projectRoot 'mobile\app\build\outputs\apk\debug\app-debug.apk'
    Write-Output "APK: $apk"
    if ($Install) {
        if ($Device) { & "$taskSdk\platform-tools\adb.exe" -s $Device install -r $apk }
        else { & "$taskSdk\platform-tools\adb.exe" install -r $apk }
        if ($LASTEXITCODE -ne 0) { throw 'APK installation failed; connect and authorize the phone.' }
    }
} finally { Pop-Location }
