# Build APK, copy to android/releases/, update app-update.json for Git-hosted updates
param(
    [string]$Changelog = "Bug fixes and improvements",
    [switch]$ForceUpdate,
    [int]$MinVersionCode = 3,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$AndroidDir = Join-Path $Root "android"
$ReleasesDir = Join-Path $AndroidDir "releases"
$GradleFile = Join-Path $AndroidDir "app\build.gradle"
$UpdateUrlFile = Join-Path $ReleasesDir "update-check-url.txt"

function Get-GitHubUrls {
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
    $tag = git -C $Root describe --tags --exact-match 2>$null
    return @{
        RawBase     = "https://raw.githubusercontent.com/$owner/$repo/$branch"
        JsDelivrBase= "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch"
        ReleaseBase = "https://github.com/$owner/$repo/releases/download"
        Tag         = $tag
    }
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

$apkItem = Get-Item $apkDest
$apkSize = $apkItem.Length
$sha256 = (Get-FileHash -Path $apkDest -Algorithm SHA256).Hash.ToLowerInvariant()

$urls = Get-GitHubUrls
$rawBase = $urls.RawBase
$jsDelivrBase = $urls.JsDelivrBase
# 主通道：jsDelivr CDN（全球 + 大陆节点，无 GitHub Raw 429 限流）
$jsonUrl = "$jsDelivrBase/android/releases/app-update.json"
$apkUrl  = "$jsDelivrBase/android/releases/$apkName"
# 备用：Raw GitHub（当 jsDelivr 失败时回退）
$fallbackUrl = "$rawBase/android/releases/$apkName"
# 第三备（可选）：GitHub Releases（需要先打 tag 并创建 Release）
$ghReleaseUrl = ""
if ($urls.Tag) {
    $ghReleaseUrl = "$($urls.ReleaseBase)/$($urls.Tag)/$apkName"
}

$update = [ordered]@{
    versionCode    = $ver.Code
    versionName    = $ver.Name
    apkUrl         = $apkUrl
    fallbackUrl    = $fallbackUrl
    apkSize        = [long]$apkSize
    sha256         = $sha256
    changelog      = $Changelog
    forceUpdate    = [bool]$ForceUpdate
    minVersionCode = if ($MinVersionCode -gt 0) { $MinVersionCode } else { 3 }
}
$jsonPath = Join-Path $ReleasesDir "app-update.json"
($update | ConvertTo-Json -Depth 5) + "`n" | Set-Content -Path $jsonPath -Encoding UTF8

$jsonUrl | Set-Content -Path $UpdateUrlFile -Encoding UTF8 -NoNewline

Write-Host ""
Write-Host "Release files ready:"
Write-Host "  APK:       android/releases/$apkName"
Write-Host "  Size:      $apkSize bytes ($([math]::Round($apkSize/1MB,2)) MB)"
Write-Host "  SHA256:    $sha256"
Write-Host "  JSON:      android/releases/app-update.json"
Write-Host "  Check URL: $jsonUrl"
if ($fallbackUrl) { Write-Host "  Fallback:  $fallbackUrl" }
Write-Host ""
Write-Host "If you want the fallback URL to work, create a GitHub Release for tag '$($urls.Tag)':"
Write-Host "  gh release create '$($urls.Tag)' '$apkDest' --title '$($ver.Name)' --notes '$Changelog'"
Write-Host ""
Write-Host "Then run:"
Write-Host "  git add android/releases/"
Write-Host "  git commit -m ""release android $($ver.Name)"""
Write-Host "  git push"