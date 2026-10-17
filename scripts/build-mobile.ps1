param([switch]$Install, [string]$Device, [string]$JavaHome = $env:JAVA_HOME, [string]$Sdk = $env:ANDROID_HOME, [string]$Gradle)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$unityAndroid = 'C:\Program Files\Unity\Hub\Editor\6000.6.0f1\Editor\Data\PlaybackEngines\AndroidPlayer'
if (!$JavaHome) {
    $localJdk = Get-ChildItem (Join-Path $projectRoot 'scratch/toolchain') -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue | Select-Object -First 1
    $JavaHome = if ($localJdk) { $localJdk.FullName } else { Join-Path $unityAndroid 'OpenJDK' }
}
if (!$Sdk) { $Sdk = Join-Path $unityAndroid 'SDK' }
if (!$Gradle) {
    $localGradle = Get-ChildItem (Join-Path $projectRoot 'scratch/toolchain') -Directory -Filter 'gradle-*' -ErrorAction SilentlyContinue | Select-Object -First 1
    $Gradle = if ($localGradle) { Join-Path $localGradle.FullName 'bin/gradle.bat' } else { 'gradle' }
}
if (!(Test-Path "$JavaHome/bin/java.exe")) { throw 'Set JAVA_HOME or pass -JavaHome with a JDK 17+ installation.' }
if (!(Test-Path "$Sdk/platforms/android-36/android.jar")) { throw 'Set ANDROID_HOME or pass -Sdk with Android SDK 36 installed.' }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
$env:GRADLE_USER_HOME = Join-Path $projectRoot 'scratch/gradle-home'
Push-Location $projectRoot
try {
    & npm.cmd run build:mobile-ui
    if ($LASTEXITCODE -ne 0) { throw 'Mobile frontend build failed' }
    & $Gradle -p mobile --no-daemon --console=plain :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
    $apk = Join-Path $projectRoot 'mobile/app/build/outputs/apk/debug/app-debug.apk'
    Write-Output "APK: $apk"
    if ($Install) {
        if ($Device) { & "$Sdk/platform-tools/adb.exe" -s $Device install -r $apk }
        else { & "$Sdk/platform-tools/adb.exe" install -r $apk }
        if ($LASTEXITCODE -ne 0) { throw 'APK installation failed; connect and authorize the phone.' }
    }
} finally { Pop-Location }
