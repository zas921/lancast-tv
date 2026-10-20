param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$JavaPath = $env:JAVA_HOME,
    [string]$BuildToolsVersion = '34.0.0',
    [int]$CompileSdk = 35
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts/common.ps1')
$projectDir = $PSScriptRoot
if (-not $SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
if (-not $SdkPath) { throw 'Set ANDROID_HOME / ANDROID_SDK_ROOT or pass -SdkPath.' }
$javacPath = Resolve-JavaTool 'javac' $JavaPath
$javaTool = Resolve-JavaTool 'java' $JavaPath
$jarTool = Resolve-JavaTool 'jar' $JavaPath
$keyTool = Resolve-JavaTool 'keytool' $JavaPath
$toolDir = Join-Path $SdkPath "build-tools/$BuildToolsVersion"
$androidJar = Join-Path $SdkPath "platforms/android-$CompileSdk/android.jar"
if (-not (Test-Path -LiteralPath $androidJar)) { throw "Install Android SDK platform $CompileSdk." }
$nativeSuffix = if ($LanCastWindowsHost) { '.exe' } else { '' }
$aaptTool = Join-Path $toolDir "aapt$nativeSuffix"
$alignTool = Join-Path $toolDir "zipalign$nativeSuffix"
if (-not (Test-Path -LiteralPath $aaptTool)) { throw "Install Android SDK Build Tools $BuildToolsVersion." }
$buildDir = Join-Path $projectDir 'build'
$distDir = Join-Path $projectDir 'dist'
# Fresh compiler output prevents stale classes from entering the APK. Keep the signing key.
$compileDir = Join-Path $buildDir ('compile-' + [guid]::NewGuid().ToString('N'))
$classesDir = Join-Path $compileDir 'classes'
$generatedDir = Join-Path $compileDir 'generated'
$dexDir = Join-Path $compileDir 'dex'
New-Item -ItemType Directory -Force -Path $buildDir,$distDir,$classesDir,$generatedDir,$dexDir | Out-Null
try {
$sourceDir = Join-Path $projectDir 'app/src/main'
[xml]$manifest = Get-Content -LiteralPath (Join-Path $sourceDir 'AndroidManifest.xml') -Raw -Encoding UTF8
$versionName = $manifest.manifest.GetAttribute('versionName', 'http://schemas.android.com/apk/res/android')
if ($versionName -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') { throw 'Invalid manifest versionName.' }
$resourceApk = Join-Path $compileDir 'resources.apk'
Invoke-Tool $aaptTool @('package','-f','-m','-M',(Join-Path $sourceDir 'AndroidManifest.xml'),'-S',(Join-Path $sourceDir 'res'),'-A',(Join-Path $sourceDir 'assets'),'-I',$androidJar,'-J',$generatedDir,'-F',$resourceApk)
$sources = @(Get-ChildItem -LiteralPath (Join-Path $sourceDir 'java'),$generatedDir -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
Invoke-Tool $javacPath (@('-encoding','UTF-8','-source','8','-target','8','-Xlint:-options','-classpath',$androidJar,'-d',$classesDir) + $sources)
$jarPath = Join-Path $compileDir 'classes.jar'
Invoke-Tool $jarTool @('cf',$jarPath,'-C',$classesDir,'.')
Invoke-Tool $javaTool @('-cp',(Join-Path $toolDir 'lib/d8.jar'),'com.android.tools.r8.D8','--min-api','22','--lib',$androidJar,'--output',$dexDir,$jarPath)
$unsignedApk = Join-Path $compileDir 'unsigned.apk'
Copy-Item -LiteralPath $resourceApk -Destination $unsignedApk -Force
Push-Location $dexDir
try { Invoke-Tool $aaptTool @('add',$unsignedApk,'classes.dex') } finally { Pop-Location }
$alignedApk = Join-Path $compileDir 'aligned.apk'
Invoke-Tool $alignTool @('-f','4',$unsignedApk,$alignedApk)
$keyPath = Join-Path $buildDir 'lancast-debug.keystore'
if (-not (Test-Path -LiteralPath $keyPath)) {
    Invoke-Tool $keyTool @('-genkeypair','-keystore',$keyPath,'-storepass','android','-keypass','android','-alias','androiddebugkey','-dname','CN=LanCast Development,O=LanCast,C=CN','-keyalg','RSA','-keysize','2048','-validity','10000')
}
$apkName = "LanCast-TV-$versionName.apk"
$apkPath = Join-Path $distDir $apkName
$signer = Join-Path $toolDir 'lib/apksigner.jar'
Invoke-Tool $javaTool @('-jar',$signer,'sign','--ks',$keyPath,'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--v1-signing-enabled','true','--v2-signing-enabled','true','--v4-signing-enabled','false','--out',$apkPath,$alignedApk)
Invoke-Tool $javaTool @('-jar',$signer,'verify','--verbose',$apkPath)
$hash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText((Join-Path $distDir 'SHA256SUMS.txt'), "$hash  $apkName`n", [Text.Encoding]::ASCII)
Write-Output "APK: $apkPath"
Write-Output "SHA256: $hash"
} finally {
    # Verify the absolute parent before deleting only this generated child directory.
    $resolvedCompile = [IO.Path]::GetFullPath($compileDir)
    $resolvedParent = [IO.Path]::GetFullPath($buildDir)
    if ([IO.Path]::GetDirectoryName($resolvedCompile) -eq $resolvedParent -and (Test-Path -LiteralPath $resolvedCompile)) {
        Remove-Item -LiteralPath $resolvedCompile -Recurse -Force
    }
}
