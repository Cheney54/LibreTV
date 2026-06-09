$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Tools = Join-Path $Root ".android-tools"
$AndroidHome = Join-Path $Tools "android-sdk"
$Gradle = Join-Path $Tools "gradle-8.7\bin\gradle.bat"
$Java = Get-ChildItem -Path (Join-Path $Tools "jdk-17") -Recurse -Filter "java.exe" | Select-Object -First 1

if (-not $Java) {
    throw "JDK not found in .android-tools\jdk-17"
}

if (-not (Test-Path $Gradle)) {
    throw "Gradle not found in .android-tools\gradle-8.7"
}

if (-not (Test-Path $AndroidHome)) {
    throw "Android SDK not found in .android-tools\android-sdk"
}

$env:JAVA_HOME = Split-Path (Split-Path $Java.FullName -Parent) -Parent
$env:ANDROID_HOME = $AndroidHome
$env:ANDROID_SDK_ROOT = $AndroidHome
$env:Path = (Join-Path $env:JAVA_HOME "bin") + ";" + (Join-Path $AndroidHome "platform-tools") + ";" + $env:Path

& $Gradle -p (Join-Path $Root "android") assembleDebug
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$Apk = Join-Path $Root "android\app\build\outputs\apk\debug\app-debug.apk"
Write-Host ""
Write-Host "APK generated: $Apk"
