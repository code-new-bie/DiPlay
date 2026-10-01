# Compiles the instrument helper to a dex jar bundled in common/assets.
# The helper runs under the adb shell user via app_process to write the BYD instrument,
# a path DiPlay's own uid cannot take. Reflection-only, so no android.jar is needed to compile.
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdk = "C:\App\JDK\jdk-21.0.10"
$d8 = "C:\Users\Li\Documents\ai_projects\byd_updatefull\android-sdk\build-tools\37.0.0\d8.bat"
$src = Join-Path $here "src\com\shilapi\xcertplay\instrumenthelper\InstrumentHelperMain.java"
$outClasses = Join-Path $here "build\classes"
$assets = Join-Path $here "..\common\src\main\assets"

Remove-Item -Recurse -Force (Join-Path $here "build") -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $outClasses | Out-Null
New-Item -ItemType Directory -Force $assets | Out-Null

# Compile for Java 8 bytecode so d8 accepts it.
& "$jdk\bin\javac.exe" --release 8 -d $outClasses $src
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# d8 emits classes.dex into an output dir; pack it into a jar app_process can load from CLASSPATH.
$dexDir = Join-Path $here "build\dex"
New-Item -ItemType Directory -Force $dexDir | Out-Null
& $d8 --min-api 29 --output $dexDir (Join-Path $outClasses "com\shilapi\xcertplay\instrumenthelper\InstrumentHelperMain.class")
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

$jar = Join-Path $assets "diplay-instrument-helper.jar"
& "$jdk\bin\jar.exe" cf $jar -C $dexDir "classes.dex"
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
Write-Host "Wrote $jar"
