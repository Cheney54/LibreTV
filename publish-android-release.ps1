# Build APK, copy to android/releases/, update app-update.json for Git-hosted updates
param(
    [string]$Changelog = "Bug fixes and improvements",
    [switch]$ForceUpdate,
    [int]$MinVersionCode = 3,
    [switch]$SkipBuild,
    [switch]$ForceBump
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
    if ($code -le 0) { throw "versionCode must be > 0 (got $code)" }
    if ([string]::IsNullOrWhiteSpace($name)) { throw "versionName is empty" }
    return @{ Code = $code; Name = $name }
}

function Read-LastPublishedVersion {
    $jsonPath = Join-Path $ReleasesDir "app-update.json"
    if (-not (Test-Path $jsonPath)) { return 0 }
    try {
        $obj = Get-Content $jsonPath -Raw | ConvertFrom-Json -ErrorAction Stop
        if ($obj -and $obj.versionCode) { return [int]$obj.versionCode }
    } catch {}
    return 0
}

$ver = Read-AppVersion
$lastCode = Read-LastPublishedVersion
if ($lastCode -gt 0 -and $ver.Code -le $lastCode -and -not $ForceBump) {
    throw "versionCode $($ver.Code) 未超过已发布版本 $lastCode。请在 $GradleFile 中递增 versionCode，或使用 -ForceBump 覆盖。"
}

if (-not $SkipBuild) {
    & (Join-Path $Root "build-android-apk.ps1") -Release
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$apkRelease = Join-Path $Root "android\app\build\outputs\apk\release\app-release.apk"
$apkDebug = Join-Path $Root "android\app\build\outputs\apk\debug\app-debug.apk"
$apkSrc = $null
$apkFlavor = $null
if (Test-Path $apkRelease) {
    $apkSrc = $apkRelease; $apkFlavor = "release"
} elseif (Test-Path $apkDebug) {
    $apkSrc = $apkDebug; $apkFlavor = "debug"
    Write-Warning "未找到 release APK，回退使用 debug APK（仅用于测试）"
} else {
    throw "未找到 APK 文件。请先运行 build-android-apk.ps1 -Release"
}
Write-Host ("[publish] 使用 {0} APK ({1:N2} MB)" -f $apkFlavor, ((Get-Item $apkSrc).Length / 1MB)) -ForegroundColor Cyan

New-Item -ItemType Directory -Force -Path $ReleasesDir | Out-Null
$apkName = "libretv-$($ver.Name).apk"
$apkDest = Join-Path $ReleasesDir $apkName
Copy-Item -Force $apkSrc $apkDest

$apkItem = Get-Item $apkDest
$apkSize = $apkItem.Length
$apkSizeMb = [math]::Round($apkSize / 1MB, 2)
if ($apkSizeMb -lt 3) {
    throw "APK 尺寸异常小: ${apkSizeMb}MB，可能构建失败。请检查 build.gradle (minifyEnabled/shrinkResources)。"
}
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
Write-Host "Release files ready:" -ForegroundColor Green
Write-Host "  APK:       android/releases/$apkName ($apkFlavor, $apkSizeMb MB)"
Write-Host "  Size:      $apkSize bytes"
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
