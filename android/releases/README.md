# Android 发布文件（提交到 Git）

本目录用于 **把 APK 和更新清单一起放进仓库**，App 通过 `raw.githubusercontent.com` 拉取 `app-update.json` 并下载 APK。

## 发布步骤

1. 修改 `android/app/build.gradle` 里的 `versionCode`、`versionName`（新版本必须 **versionCode 递增**）。
2. 在项目根目录执行：

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\publish-android-release.ps1 -Changelog "更新说明"
```

3. 提交并推送（**务必包含 .apk 和 app-update.json**）：

```bash
git add android/releases/
git commit -m "release android x.x.x"
git push
```

4. 再执行一次 `publish-android-release.ps1 -SkipBuild` 或完整打包，使本机 APK 内嵌的 `UPDATE_CHECK_URL` 与仓库一致（首次配置后每次发布建议重新 `assembleDebug` 安装）。

## 文件说明

| 文件 | 说明 |
|------|------|
| `libretv-x.y.z.apk` | 安装包 |
| `app-update.json` | 版本号、下载地址、更新说明 |
| `update-check-url.txt` | 构建时写入 App 的检查地址（由脚本自动生成） |

## 非 GitHub

若使用 Gitee 等，请手动改 `app-update.json` 里的 `apkUrl` 为对应 **raw 直链**，并在 `android/app/build.gradle` 或运行脚本后把 `update-check-url.txt` 改为你的 `app-update.json` 直链。