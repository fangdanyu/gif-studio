param(
  [Parameter(Mandatory=$true)][string]$Token,
  [string]$Repo = "gif-studio"
)

# 读取用户名
$headers = @{ Authorization = "Bearer $Token"; "User-Agent" = "gif-studio-release"; Accept = "application/vnd.github+json" }
$user = Invoke-RestMethod -Uri "https://api.github.com/user" -Headers $headers
$owner = $user.login
Write-Host "GitHub 用户：$owner"

$repoDir = "C:\Users\demon\Desktop\gif"
Set-Location $repoDir

# 1) 创建仓库（已存在则跳过）
$exists = $false
try { Invoke-RestMethod -Uri "https://api.github.com/repos/$owner/$Repo" -Headers $headers | Out-Null; $exists = $true } catch {}
if (-not $exists) {
  $body = @{ name = $Repo; description = "GIF Studio · 安卓 GIF 编辑器：视频/图片一键转 GIF，裁剪/文字贴纸/涂鸦/按体积导出，全程离线"; private = $false; has_issues = $true; has_wiki = $false } | ConvertTo-Json
  Invoke-RestMethod -Uri "https://api.github.com/user/repos" -Method Post -Headers $headers -Body $body -ContentType "application/json" | Out-Null
  Write-Host "已创建公开仓库 $owner/$Repo"
} else { Write-Host "仓库已存在 $owner/$Repo" }

# 2) 替换站点与 README 里的占位用户名
Get-ChildItem -Path "$repoDir\docs\index.html", "$repoDir\README.md" | ForEach-Object {
  $t = [IO.File]::ReadAllText($_.FullName)
  [IO.File]::WriteAllText($_.FullName, $t.Replace("GITHUB_USER", $owner))
}

# 3) 提交并推送
git config user.name $owner
git config user.email "$owner@users.noreply.github.com"
git add -A
git commit -m "GIF Studio 1.1.0：安卓原生 GIF 编辑器（自研编解码 + 官网 + 开源清单）" | Out-Null
git remote remove origin 2>$null
git remote add origin "https://$owner`:$Token@github.com/$owner/$Repo.git"
git push -u origin main --quiet
Write-Host "已推送 main"

# 4) 发布 Release 并上传两个 APK
$tag = "v1.1.0"
$relBody = @"
## GIF Studio 1.1.0（2026-11）

安卓原生 GIF 编辑器，全程离线、无水印、无广告。

**本版包含（第三批改造后）**
- 裁剪固定比例 1:1 / 3:4 / 4:3 / 16:9 / 9:16，选框只按所选比例伸缩，四角控点内缩
- 文字 / emoji / 相册图片贴纸支持双指捏合缩放与旋转
- 导出方式点选后即时显示预估文件大小
- 多图合成选完图直接成片（不再弹帧率选择）
- 未导出退出时提示「还没有导出」，可导出 / 放弃 / 继续编辑
- 首页底部显示「产品设计：FDY　版本：1.1.0」

**安装**：Android 8.0+，下载 ``GifStudio-1.1.0-release.apk`` 安装即可；debug 版用于对比排查。
**校验**：release 签名证书 SHA-256 ``6c07f745af8345249c4dd99439a3c341db67d1fd19930c77b7d793dd3082e003``（与 1.0.0 一致，可覆盖升级）。
"@

$release = @{ tag_name = $tag; name = "GIF Studio 1.1.0"; body = $relBody; draft = $false; prerelease = $false } | ConvertTo-Json
try {
  $r = Invoke-RestMethod -Uri "https://api.github.com/repos/$owner/$Repo/releases" -Method Post -Headers $headers -Body $release -ContentType "application/json"
} catch {
  $r = Invoke-RestMethod -Uri "https://api.github.com/repos/$owner/$Repo/releases/tags/$tag" -Headers $headers
}
$uploadUrl = ($r.upload_url -replace "\{.*\}", "")

foreach ($apk in @("GifStudio-1.1.0-release.apk", "GifStudio-1.1.0-debug.apk")) {
  $path = Join-Path $repoDir "apk\$apk"
  $up = Invoke-RestMethod -Uri "$uploadUrl`?name=$apk" -Method Post -Headers $headers -ContentType "application/vnd.android.package-archive" -InFile $path
  Write-Host "已上传 $apk ($([math]::Round($up.size/1MB,2)) MB)"
}

# 5) 开启 GitHub Pages（main /docs）
$pages = @{ source = @{ branch = "main"; path = "/docs" } } | ConvertTo-Json -Depth 4
try {
  Invoke-RestMethod -Uri "https://api.github.com/repos/$owner/$Repo/pages" -Method Post -Headers $headers -Body $pages -ContentType "application/json" | Out-Null
  Write-Host "Pages 已创建（main /docs）"
} catch {
  Invoke-RestMethod -Uri "https://api.github.com/repos/$owner/$Repo/pages" -Method Put -Headers $headers -Body $pages -ContentType "application/json" | Out-Null
  Write-Host "Pages 已更新（main /docs）"
}
Start-Sleep -Seconds 8
$p = Invoke-RestMethod -Uri "https://api.github.com/repos/$owner/$Repo/pages" -Headers $headers
Write-Host "Pages 状态：$($p.status) 地址：$($p.html_url)"
Write-Host "仓库地址：https://github.com/$owner/$Repo"
