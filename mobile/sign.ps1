$apksigJar = (Get-ChildItem -Path "C:\Users\ROHIT\.gradle\caches\modules-2\files-2.1\com.android.tools.build\apksig" -Filter "*.jar" -Recurse)[0].FullName
$java = "C:\Program Files\Java\jre1.8.0_421\bin\java.exe"
$cp = "mobile\signer.jar;$apksigJar"

Write-Host "Using apksig: $apksigJar"
& $java -cp $cp SignerKt "mobile\app-unsigned.apk" "mobile\app-signed.apk" "mobile\debug.keystore"
if ($LASTEXITCODE -eq 0) {
    Write-Host "Signing SUCCESS!"
    Get-Item "mobile\app-signed.apk" | Select-Object LastWriteTime, Length
} else {
    Write-Host "Signing FAILED with code $LASTEXITCODE"
}
