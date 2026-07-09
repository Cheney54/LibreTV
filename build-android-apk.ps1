param(
    [switch]$Release
)
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

$GradleFile = Join-Path $Root "android\app\build.gradle"
$GradleText = Get-Content $GradleFile -Raw
$HasReleaseSigning = $false
if ($GradleText -match 'signingConfigs\s*\{[\s\S]*?release\s*\{') {
    $HasReleaseSigning = $true
}

$LocalProps = Join-Path $Root "android\local.properties"
if ($HasReleaseSigning -and (Test-Path $LocalProps)) {
    $props = Get-Content $LocalProps
    $needKeys = @('RELEASE_KEYSTORE', 'RELEASE_STORE_PASSWORD', 'RELEASE_KEY_ALIAS', 'RELEASE_KEY_PASSWORD')
    $foundAll = $true
    foreach ($k in $needKeys) {
        if (-not ($props -match "^$k\s*=")) { $foundAll = $false; break }
    }
    if (-not $foundAll) { $HasReleaseSigning = $false }
} else {
    $HasReleaseSigning = $false
}

$UseRelease = $Release.IsPresent -or $HasReleaseSigning
if ($UseRelease) {
    Write-Host ("[build] -> assembleRelease (signing: " + $HasReleaseSigning + ")") -ForegroundColor Cyan
    & $Gradle -p (Join-Path $Root "android") assembleRelease
    $BuildExit = $LASTEXITCODE
    if ($BuildExit -ne 0 -and -not $Release.IsPresent) {
        Write-Warning "assembleRelease failed, falling back to assembleDebug..."
        & $Gradle -p (Join-Path $Root "android") assembleDebug
        $BuildExit = $LASTEXITCODE
        $UseRelease = $false
    }
    if ($BuildExit -ne 0) { exit $BuildExit }
} else {
    Write-Host "[build] -> assembleDebug (no release signing configured)" -ForegroundColor DarkYellow
    & $Gradle -p (Join-Path $Root "android") assembleDebug
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

if ($UseRelease) {
    $Apk = Join-Path $Root "android\app\build\outputs\apk\release\app-release.apk"
    if (-not (Test-Path $Apk)) {
        $Apk = Join-Path $Root "android\app\build\outputs\apk\release\app-release-unsigned.apk"
    }
} else {
    $Apk = Join-Path $Root "android\app\build\outputs\apk\debug\app-debug.apk"
}

if (-not (Test-Path $Apk)) {
    throw ("APK output not found: " + $Apk)
}
Write-Host ""
Write-Host ("APK generated: " + $Apk) -ForegroundColor Green
$item = Get-Item $Apk
$sizeMb = [math]::Round($item.Length / 1MB, 2)
Write-Host ("Size: " + $sizeMb + " MB")
