$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$taskJava = 'C:\Program Files\Unity\Hub\Editor\6000.5.2f1\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK\bin'
$output = Join-Path $projectRoot 'mobile\build\acoustic-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$source = Join-Path $projectRoot 'mobile\app\src\main\java\in\itantra\mobile'
$files = @('ItpPacket.java','AcousticConfig.java','AcousticFrame.java','AcousticModem.java','AcousticLink.java') | ForEach-Object { Join-Path $source $_ }
& "$taskJava\javac.exe" -encoding UTF-8 -d $output $files (Join-Path $projectRoot 'mobile\tests\AcousticTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Acoustic test compilation failed' }
& "$taskJava\java.exe" -cp $output in.itantra.mobile.AcousticTest
if ($LASTEXITCODE -ne 0) { throw 'Acoustic tests failed' }
