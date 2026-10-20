param([string]$JavaPath=$env:JAVA_HOME, [switch]$Multicast)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'scripts/common.ps1')
$javacPath=Resolve-JavaTool 'javac' $JavaPath
$javaTool=Resolve-JavaTool 'java' $JavaPath
$testOutput=Join-Path $PSScriptRoot 'build/test-classes'
New-Item -ItemType Directory -Force -Path $testOutput | Out-Null
$source=Join-Path $PSScriptRoot 'app/src/main/java/com/lancast/tv'
$files=@('Net.java','HttpWire.java','Renderer.java','Dlna.java','QrCode.java') | ForEach-Object { Join-Path $source $_ }
Invoke-Tool $javacPath (@('-encoding','UTF-8','-d',$testOutput) + $files + (Join-Path $PSScriptRoot 'tests/ProtocolTest.java'))
$arguments=@('-cp',$testOutput,'com.lancast.tv.ProtocolTest')
if($Multicast) { $arguments += '--multicast' }
Push-Location $PSScriptRoot
try {
    Invoke-Tool $javaTool $arguments
} finally { Pop-Location }
