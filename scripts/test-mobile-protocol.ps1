$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$taskJava = 'C:\Program Files\Unity\Hub\Editor\6000.5.2f1\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK\bin'
$output = Join-Path $projectRoot 'mobile\build\protocol-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
& "$taskJava\javac.exe" -encoding UTF-8 -d $output (Join-Path $projectRoot 'mobile\app\src\main\java\in\itantra\mobile\ItpPacket.java') (Join-Path $projectRoot 'mobile\app\src\main\java\in\itantra\mobile\FrameIO.java') (Join-Path $projectRoot 'mobile\tests\ProtocolTest.java') (Join-Path $projectRoot 'mobile\tests\TransportTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Protocol test compilation failed' }
& "$taskJava\java.exe" -cp $output in.itantra.mobile.ProtocolTest
if ($LASTEXITCODE -ne 0) { throw 'Protocol tests failed' }
& "$taskJava\java.exe" -cp $output in.itantra.mobile.TransportTest
if ($LASTEXITCODE -ne 0) { throw 'Transport integration tests failed' }
