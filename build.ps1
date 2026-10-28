# Build without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner
# Usage:  powershell -ExecutionPolicy Bypass -File .\build.ps1
# (ASCII only on purpose: Windows PowerShell 5.1 parses .ps1 as ANSI without a BOM)
param(
    [string]$Sdk     = 'C:\AndroidBuild\sdk',
    [string]$Jdk     = 'C:\AndroidBuild\tools\jdk\jdk-17.0.20.1+1',
    [string]$Stage   = 'C:\AndroidBuild\build\wig',
    [string]$Version = '1.2'
)

# versionName -> versionCode: 1.2.3 => 10203 (same rule as the in-app updater)
function Get-VersionCode([string]$v) {
    $parts = $v.Split('.')
    $major = 0; $minor = 0; $patch = 0
    if ($parts.Length -gt 0) { [void][int]::TryParse($parts[0], [ref]$major) }
    if ($parts.Length -gt 1) { [void][int]::TryParse($parts[1], [ref]$minor) }
    if ($parts.Length -gt 2) { [void][int]::TryParse(($parts[2] -replace '\D',''), [ref]$patch) }
    return $major * 10000 + $minor * 100 + $patch
}
$versionCode = Get-VersionCode $Version

$ErrorActionPreference = 'Stop'
$root       = $PSScriptRoot
$main       = Join-Path $root 'app\src\main'
$buildTools = Join-Path $Sdk 'build-tools\34.0.0'
$androidJar = Join-Path $Sdk 'platforms\android-34\android.jar'
$aapt2      = Join-Path $buildTools 'aapt2.exe'
$d8         = Join-Path $buildTools 'd8.bat'
$zipalign   = Join-Path $buildTools 'zipalign.exe'
$apksigner  = Join-Path $buildTools 'apksigner.bat'
$javac      = Join-Path $Jdk 'bin\javac.exe'
$keytool    = Join-Path $Jdk 'bin\keytool.exe'

foreach ($p in @($androidJar, $aapt2, $d8, $zipalign, $apksigner, $javac, $keytool)) {
    if (-not (Test-Path -LiteralPath $p)) { throw "Missing build tool: $p" }
}

$env:JAVA_HOME = $Jdk
$env:PATH = "$Jdk\bin;$buildTools;$env:PATH"

# Clean staging dir (ASCII path only, avoids tool issues with non-ASCII paths)
if (Test-Path -LiteralPath $Stage) { Remove-Item -LiteralPath $Stage -Recurse -Force }
$res     = Join-Path $Stage 'res'
$java    = Join-Path $Stage 'java'
$gen     = Join-Path $Stage 'gen'
$classes = Join-Path $Stage 'classes'
$dex     = Join-Path $Stage 'dex'
New-Item -ItemType Directory -Force -Path $Stage, $res, $java, $gen, $classes, $dex | Out-Null

Copy-Item -Path (Join-Path $main 'res\*')  -Destination $res  -Recurse -Force
Copy-Item -Path (Join-Path $main 'java\*') -Destination $java -Recurse -Force
Copy-Item -Path (Join-Path $main 'AndroidManifest.xml') -Destination $Stage -Force

$androidJarArg = $androidJar.Replace('\', '/')
$manifest = Join-Path $Stage 'AndroidManifest.xml'
$resZip   = Join-Path $Stage 'res.zip'
$unsigned = Join-Path $Stage 'app-unsigned.apk'

Write-Host '[1/6] aapt2 compile' -ForegroundColor Cyan
& $aapt2 compile --dir $res -o $resZip
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Write-Host '[2/6] aapt2 link' -ForegroundColor Cyan
& $aapt2 link -o $unsigned -I $androidJarArg --manifest $manifest -R $resZip `
    --java $gen --min-sdk-version 23 --target-sdk-version 28 `
    --version-code $versionCode --version-name $Version --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '[3/6] javac' -ForegroundColor Cyan
$sources = @()
$sources += Get-ChildItem -Path $java -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += Get-ChildItem -Path $gen  -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcList = Join-Path $Stage 'sources.txt'
$lines = $sources | ForEach-Object { '"' + ($_.Replace('\', '/')) + '"' }
[System.IO.File]::WriteAllLines($srcList, [string[]]$lines, (New-Object System.Text.UTF8Encoding($false)))
& $javac -encoding UTF-8 -source 1.8 -target 1.8 -nowarn `
    -classpath $androidJar `
    -d $classes "@$srcList"
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '[4/6] d8 (dex)' -ForegroundColor Cyan
$classFiles = Get-ChildItem -Path $classes -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $d8 --release --min-api 23 --lib $androidJarArg --output $dex @classFiles
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
$dexFile = Join-Path $dex 'classes.dex'
if (-not (Test-Path -LiteralPath $dexFile)) { throw 'classes.dex not generated' }

Write-Host '[5/6] package dex + zipalign' -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $entry = $zip.GetEntry('classes.dex')
    if ($entry) { $entry.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, $dexFile, 'classes.dex',
        [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally {
    $zip.Dispose()
}
$aligned = Join-Path $Stage 'app-aligned.apk'
& $zipalign -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Host '[6/6] sign' -ForegroundColor Cyan
$ksDir = Join-Path $root 'keystore'
New-Item -ItemType Directory -Force -Path $ksDir | Out-Null
$ks = Join-Path $ksDir 'debug.keystore'
if (-not (Test-Path -LiteralPath $ks)) {
    & $keytool -genkeypair -keystore $ks -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=WiFi Guard, OU=Dev, O=WifiGuard, L=CN, S=CN, C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$final = Join-Path $dist ("WiFiGuard-" + $Version + ".apk")
& $apksigner sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --out $final $aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }
& $apksigner verify --print-certs $final | Select-Object -First 6

$size = [math]::Round((Get-Item -LiteralPath $final).Length / 1KB, 1)
Write-Host ''
Write-Host "OK: $final  ($size KB)" -ForegroundColor Green
& $aapt2 dump badging $final | Select-Object -First 3
