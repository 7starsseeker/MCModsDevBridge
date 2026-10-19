<#
.SYNOPSIS
  MCDev Probe 套件安装器 —— 把「对应加载器的那一份 jar」放进某个服务端/客户端的 mods/，可选装 DSH 插件。

.DESCRIPTION
  发布物是**一个套件**（一个 zip / 一个版本号 / 一份清单），不是三个各自发布的 jar。
  三个 jar 仍在包里（加载器入口不同，无法合并），但用户只需要跟一个东西打交道：
  解开套件 → 跑本脚本 → 选加载器 → 指目录。

  jar 解析顺序（先到先用）：
    1) -Jar 显式指定；
    2) 套件布局：<脚本目录>\mods\*<loader>*.jar；
    3) 开发布局：<repo>\lab\<loader>\build\libs\*.jar（跳过 *sources*）。
  套件目录里有 SHA256SUMS.txt 时顺便校验，校验不过直接失败。

.PARAMETER Loader
  fabric | forge | neoforge

.PARAMETER Target
  目标实例目录（含或不含 mods 都行）：脚本会放进 <Target>\mods\。
  **目录必须已存在** —— 拼错路径时宁可报错，也不要凭空建一棵没人用的树。

.PARAMETER DshPlugin
  顺便把 mcdev-devrun 插件装进 DSH（= 跑 install-dsh-plugin.ps1），装完需重启 DSH。

.EXAMPLE
  pwsh -NoProfile -File .\install.ps1 -Loader fabric -Target "<MC_ROOT>\Server"
  pwsh -NoProfile -File .\install.ps1 -Loader forge -Target "<MC_ROOT>\Server1" -DshPlugin
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('fabric', 'forge', 'neoforge')]
    [string]$Loader,
    [Parameter(Mandatory = $true)]
    [string]$Target,
    [string]$Jar,
    [switch]$DshPlugin,
    [string]$DshHome,
    [string]$PluginSource,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$Here = $PSScriptRoot
# 套件布局：脚本在 <套件>\scripts\ 下，jar 在 <套件>\mods\；
# 开发布局：脚本在 <repo>\scripts\ 下，jar 在 <repo>\lab\<loader>\build\libs\。
$Bundle = if (Test-Path (Join-Path $Here 'mods')) { $Here } else { Split-Path -Parent $Here }
$RepoRoot = if (Test-Path (Join-Path $Bundle 'lab')) { $Bundle } else { Split-Path -Parent $Here }

function Fail($msg) { Write-Host "❌ $msg"; exit 1 }

# ---- 1. 找 jar ----
$candidates = @()
if ($Jar) { $candidates += $Jar }
$candidates += (Join-Path $Here "mods\$Loader.jar")
$candidates += (Get-ChildItem (Join-Path $Bundle 'mods') -Filter "*$Loader*.jar" -ErrorAction SilentlyContinue | ForEach-Object FullName)
$candidates += (Get-ChildItem (Join-Path $RepoRoot "lab\$Loader\build\libs") -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*sources*' } | ForEach-Object FullName)

$jarPath = $candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $jarPath) { Fail "找不到 $Loader 的 jar（试过 -Jar、$Bundle\mods\、lab\$Loader\build\libs）" }
$jarItem = Get-Item $jarPath
$hash = (Get-FileHash $jarItem.FullName -Algorithm SHA256).Hash.ToLower()

# ---- 2. 有清单就校验 ----
$sumFile = Join-Path $Bundle 'SHA256SUMS.txt'
$checked = 'n/a'
if (Test-Path $sumFile) {
    $line = Get-Content $sumFile | Where-Object { $_ -match [regex]::Escape($jarItem.Name) } | Select-Object -First 1
    if ($line) {
        $expect = ($line -split '\s+')[0].ToLower()
        if ($expect -ne $hash) { Fail "$($jarItem.Name) 的 SHA256 与 SHA256SUMS.txt 不一致（期望 $expect，实际 $hash）" }
        $checked = 'ok'
    } else { $checked = 'not-listed' }
}

# ---- 3. 目标目录 ----
if (-not (Test-Path $Target)) { Fail "目标目录不存在：$Target（拼错了？本脚本不会凭空建实例目录）" }
$modsDir = if (Test-Path (Join-Path $Target 'mods')) { Join-Path $Target 'mods' } else { New-Item -ItemType Directory -Force -Path (Join-Path $Target 'mods') | Out-Null; Join-Path $Target 'mods' }
$dest = Join-Path $modsDir $jarItem.Name

Write-Host "加载器   : $Loader"
Write-Host "jar      : $($jarItem.FullName)  ($($jarItem.Length) B)"
Write-Host "sha256   : $hash  (清单校验: $checked)"
Write-Host "目标     : $dest"

if ($DryRun) { Write-Host "[dryrun] 未做任何改动。"; return }

Copy-Item $jarItem.FullName -Destination $dest -Force
Write-Host "✅ 已放入 $dest"

# 同名旧 jar 不同版本会同时被加载 —— 明确点出来（按名字比，避免相对/绝对路径把自己也列进去）
$others = @(Get-ChildItem $modsDir -Filter '*probe*.jar' | Where-Object { $_.Name -ne $jarItem.Name })
if ($others.Count -gt 0) {
    Write-Host "⚠ mods/ 里还有别的探针 jar，同时加载会冲突：" -ForegroundColor Yellow
    $others | ForEach-Object { Write-Host "   $($_.Name)" }
}

# ---- 4. 可选：装 DSH 插件 ----
if ($DshPlugin) {
    $inst = Join-Path $Here 'install-dsh-plugin.ps1'
    if (-not (Test-Path $inst)) { $inst = Join-Path (Join-Path $Bundle 'scripts') 'install-dsh-plugin.ps1' }
    if (-not (Test-Path $inst)) { Fail "找不到 install-dsh-plugin.ps1（套件里应有它）" }
    if (-not $PluginSource) {
        foreach ($c in @((Join-Path $Bundle 'dsh-plugin\mcdev-devrun'), (Join-Path $RepoRoot 'tools\dsh-plugin\mcdev-devrun'))) {
            if (Test-Path $c) { $PluginSource = $c; break }
        }
    }
    if (-not $PluginSource) { Fail "找不到插件源码目录（-PluginSource 指定）" }
    $argv = @('-NoProfile', '-File', $inst, '-PluginSource', $PluginSource)
    if ($DshHome) { $argv += @('-DshHome', $DshHome) }
    & pwsh @argv
    if ($LASTEXITCODE -ne 0) { Fail "DSH 插件安装失败（exit $LASTEXITCODE）" }
    Write-Host "⚠ 插件已写入，**重启 DSH 后**工具才出现（旧会话的工具表不会刷新，要新建会话）。" -ForegroundColor Yellow
}
