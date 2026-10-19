<#
.SYNOPSIS
  把 mcdev-devrun 插件装进（或从）DSH 的 home 层挂载表。

.DESCRIPTION
  安装 = 两件事，都可逆：
    1) 复制本仓 `tools/dsh-plugin/mcdev-devrun/` 到 `$DSH_HOME/plugins/mcdev-devrun/`；
    2) 在 `$DSH_HOME/cordis.patch.yml` 末尾追加一段带注释的 `- insert:`。

  为什么用 home 层而不是 profiles/desktop 层：home 层按定义叠在**每个** profile 之上，
  一次覆盖、换 profile 不失效（和 win-encoding 同一个理由）。

  安全措施：
    · 改 `cordis.patch.yml` 之前先备份成 `cordis.patch.yml.bak-<时间戳>`；
    · 幂等：已有 `id: mcdev-devrun` 就只更新插件文件、不重复插 mount 段；
    · `-Mode dryrun` 只打印将要做的事，不落任何盘。

  生效条件：**必须重启 DSH**（配置只在加载时读取，热改无效）。
  回滚：`-Mode uninstall`，或直接用 `cordis.patch.yml.bak-*` 覆盖回去。

.PARAMETER Mode
  install（默认）| uninstall | dryrun

.PARAMETER DshHome
  DSH home 目录：默认取环境变量 DSH_HOME；没有就报错（不回退到任何写死路径）。

.PARAMETER PluginSource
  插件源码目录，默认 `<repo>/tools/dsh-plugin/mcdev-devrun`。
  发布套件（`dist/mcdev-probe-suite-*`）里传 `-PluginSource .\dsh-plugin\mcdev-devrun`。

.EXAMPLE
  pwsh -NoProfile -File scripts\install-dsh-plugin.ps1 -Mode dryrun
  pwsh -NoProfile -File scripts\install-dsh-plugin.ps1
  pwsh -NoProfile -File scripts\install-dsh-plugin.ps1 -Mode uninstall
#>
[CmdletBinding()]
param(
    [ValidateSet('install', 'uninstall', 'dryrun')]
    [string]$Mode = 'install',
    [string]$DshHome = $env:DSH_HOME,
    [string]$PluginSource
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot          # 工作区根
$src = if ($PluginSource) { (Resolve-Path $PluginSource).Path } else { Join-Path $root 'tools\dsh-plugin\mcdev-devrun' }
if (-not $DshHome) { throw '不知道 DSH home 在哪：设置环境变量 DSH_HOME，或传 -DshHome <目录>。' }
$dst = Join-Path $DshHome 'plugins\mcdev-devrun'
$patch = Join-Path $DshHome 'cordis.patch.yml'
$id = 'mcdev-devrun'
$fileUrl = "file:///$((Join-Path $DshHome "plugins\$id\dsh.mjs") -replace '\\', '/')"

if (-not (Test-Path $src)) { throw "找不到插件源目录：$src" }
if (-not (Test-Path $patch)) { throw "找不到挂载表：$patch（DSH_HOME 指错？）" }

$utf8 = New-Object System.Text.UTF8Encoding($false)
$text = [System.IO.File]::ReadAllText($patch)
$mounted = $text -match "(?m)^\s*-\s*id:\s*$([regex]::Escape($id))\s*$"

function Show-Plan {
    Write-Host "工作区     : $root"
    Write-Host "DSH home   : $DshHome"
    Write-Host "插件源     : $src"
    Write-Host "安装目标   : $dst"
    Write-Host "挂载表     : $patch"
    Write-Host "已挂载     : $mounted"
    if ($Mode -eq 'install' -and -not $mounted) {
        Write-Host "将追加 mount：`n- insert:`n    - id: $id`n      name: `"$fileUrl`""
    }
}

Show-Plan
if ($Mode -eq 'dryrun') { Write-Host "`n[dryrun] 未做任何改动。"; return }

if ($Mode -eq 'install') {
    New-Item -ItemType Directory -Force -Path (Split-Path $dst) | Out-Null
    if (Test-Path $dst) { Remove-Item $dst -Recurse -Force }
    Copy-Item $src $dst -Recurse -Force
    Write-Host "已复制插件 → $dst"
    # 把工作区根落到插件目录（不进仓库）：dsh.mjs 靠它找工作区，换机器时由本脚本重写。
    [System.IO.File]::WriteAllText((Join-Path $dst 'mcdev-root.txt'), $root, $utf8)

    if ($mounted) {
        Write-Host "挂载表里已有 id: $id，跳过追加（幂等）。"
    } else {
        $backup = "$patch.bak-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
        Copy-Item $patch $backup -Force
        Write-Host "已备份挂载表 → $backup"
        $block = @"

# ── MC 探针端到端编排（mcdev-devrun）──────────────────────────────────────────
# $(Get-Date -Format 'yyyy-MM-dd') added. 把本仓 scripts/devrun.ps1 包成两个工具：
#   mcdev_devrun     起服→起客户端→连服+op→跑场景→取证→收尾（-Family all 串行跑三家）
#   mcdev_scenarios  列出可用的场景文件
# 薄包装：编排逻辑全在 scripts/devrun.ps1，插件只做参数校验与输出裁剪。
# 关掉它：给下面这行加 ``disabled: true``（或整段删掉），重启 DSH 即可。
- insert:
    - id: $id
      name: "$fileUrl"
"@
        [System.IO.File]::WriteAllText($patch, ($text.TrimEnd() + $block + "`n"), $utf8)
        Write-Host "已追加 mount 段 → $patch"
    }
    Write-Host "`n✅ 安装完成。**重启 DSH 后**生效（配置只在加载时读取）。"
    Write-Host "   自测（不需要 DSH）：node tools\dsh-plugin\selftest.mjs"
    Write-Host "   回滚：pwsh -NoProfile -File scripts\install-dsh-plugin.ps1 -Mode uninstall"
    return
}

# uninstall
if (Test-Path $dst) {
    $backup = "$patch.bak-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Copy-Item $patch $backup -Force
    Write-Host "已备份挂载表 → $backup"
    $lines = [System.IO.File]::ReadAllLines($patch, $utf8)
    $out = New-Object System.Collections.Generic.List[string]
    $i = 0
    while ($i -lt $lines.Count) {
        $line = $lines[$i]
        # 命中注释块开头时整段跳过（注释 5 行 + insert 两行），直到本段结束
        if ($line -match '── MC 探针端到端编排') {
            $i++
            while ($i -lt $lines.Count -and $lines[$i] -notmatch "id:\s*$([regex]::Escape($id))") { $i++ }
            if ($i -lt $lines.Count) { $i++ }   # 跳过 id 行
            if ($i -lt $lines.Count -and $lines[$i] -match 'name:\s*"file:///') { $i++ }  # 跳过 name 行
            $i++                                # 跳过 name 行之后可能的空行
            continue
        }
        $out.Add($line)
        $i++
    }
    [System.IO.File]::WriteAllText($patch, (($out -join "`n").TrimEnd() + "`n"), $utf8)
    Write-Host "已从挂载表移除 id: $id"

    Remove-Item $dst -Recurse -Force
    Write-Host "已删除插件目录 → $dst"
    Write-Host "`n✅ 卸载完成。重启 DSH 后工具消失。"
} else {
    Write-Host "插件目录不存在，无需卸载：$dst"
}
