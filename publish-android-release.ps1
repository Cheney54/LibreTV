# Build APK, copy to android/releases/, update app-update.json for Git-hosted updates
param(
    [string]$Changelog = "Bug fixes and improvements",
    [switch]$ForceUpdate,
    [int]$MinVersionCode = 0,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$AndroidDir = Join-Path $Root "android"
$ReleasesDir = Join-Path $AndroidDir "releases"
$GradleFile = Join-Path $AndroidDir "app\build.gradle"
$UpdateUrlFile = Join-Path $ReleasesDir "update-check-url.txt"

function Get-GitHubRawBase {
    $remote = git -C $Root remote get-url origin 2>$null
    if (-not $remote) {
        throw "No git remote origin. Run: git remote add origin ..."
    }
    $remote = $remote.Trim() -replace '\.git$', ''
    if ($remote -match 'github\.com[:/](.+)/(.+)$') {
        $owner = $Matches[1]
        $repo = $Matches[2]
    } else {
        throw "GitHub remote required. Current: $remote"
    }
    $branch = (git -C $Root rev-parse --abbrev-ref HEAD 2>$null)
    if (-not $branch) { $branch = "main" }
    return "https://raw.githubusercontent.com/$owner/$repo/$branch"
}

function Read-AppVersion {
    $text = Get-Content $GradleFile -Raw
    if ($text -notmatch 'versionCode\s+(\d+)') { throw "Cannot parse versionCode" }
    $code = [int]$Matches[1]
    if ($text -notmatch 'versionName\s+"([^"]+)"') { throw "Cannot parse versionName" }
    $name = $Matches[1]
    return @{ Code = $code; Name = $name }
}

if (-not $SkipBuild) {
    & (Join-Path $Root "build-android-apk.ps1")
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$ver = Read-AppVersion
$apkSrc = Join-Path $Root "android\app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apkSrc)) {
    throw "APK not found: $apkSrc"
}

New-Item -ItemType Directory -Force -Path $ReleasesDir | Out-Null
$apkName = "libretv-$($ver.Name).apk"
$apkDest = Join-Path $ReleasesDir $apkName
Copy-Item -Force $apkSrc $apkDest

$rawBase = Get-GitHubRawBase
$jsonUrl = "$rawBase/android/releases/app-update.json"
$apkUrl = "$rawBase/android/releases/$apkName"

$update = [ordered]@{
    versionCode    = $ver.Code
    versionName    = $ver.Name
    apkUrl         = $apkUrl
    changelog      = $Changelog
    forceUpdate    = [bool]$ForceUpdate
    minVersionCode = if ($MinVersionCode -gt 0) { $MinVersionCode } else { $ver.Code }
}
$jsonPath = Join-Path $ReleasesDir "app-update.json"
($update | ConvertTo-Json) + "`n" | Set-Content -Path $jsonPath -Encoding UTF8

$jsonUrl | Set-Content -Path $UpdateUrlFile -Encoding UTF8 -NoNewline

Write-Host ""
Write-Host "Release files ready:"
Write-Host "  APK:  android/releases/$apkName"
Write-Host "  JSON: android/releases/app-update.json"
Write-Host "  Check URL: $jsonUrl"
Write-Host ""
Write-Host "git add android/releases/"
Write-Host "git commit -m ""release android $($ver.Name)"""
Write-Host "git push"