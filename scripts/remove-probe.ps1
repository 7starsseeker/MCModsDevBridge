<#
.SYNOPSIS
按安装记录把探针从一个游戏目录里精确卸载。

.DESCRIPTION
只删自己放进去的东西：
  · 安装记录里登记、且哈希仍然一致的文件（默认是 mods\mcdev_probe-*.jar）；
  · 配置里自己写的那几个键（如果配置是本次安装创建的，整个文件删掉）；
  · 安装记录本身。

不碰用户的其它 mod、不碰加载器、不碰世界存档。哈希对不上时默认停下 —— 那说明这个文件
在装完之后被人改过，删它就不再是「撤销自己的操作」了。

.PARAMETER Target
游戏目录（与 install-probe.ps1 的 -Target 相同）。

.PARAMETER Force
哈希不一致也删（明确知道自己在做什么时才用）。

.PARAMETER WhatIf
只报告将删什么，不落盘。

.EXAMPLE
pwsh -File scripts\remove-probe.ps1 -Target 'C:\Users\me\.minecraft'
#>
param(
    [Parameter(Mandatory = $true)][string]$Target,
    [switch]$Force,
    [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'

function Fail([string]$Message) {
    Write-Host "[remove-probe] 错误：$Message" -ForegroundColor Red
    exit 1
}

function Info([string]$Message) {
    Write-Host "[remove-probe] $Message"
}

if (-not (Test-Path -LiteralPath $Target)) {
    Fail "目标目录不存在：$Target"
}
$TargetPath = (Resolve-Path -LiteralPath $Target).Path
$ConfigFile = Join-Path $TargetPath 'config\mcdev-probe.properties'
$ManifestFile = Join-Path $TargetPath 'config\mcdev-probe.manifest.json'

if (-not (Test-Path -LiteralPath $ManifestFile)) {
    $stray = @(Get-ChildItem -LiteralPath (Join-Path $TargetPath 'mods') -Filter 'mcdev_probe-*.jar' -ErrorAction SilentlyContinue)
    if ($stray.Count -gt 0) {
        Info "没有安装记录，但 mods\ 里有探针 jar：$($stray.Name -join ', ')"
        Info "不做任何改动。确认要删的话，手工删掉它，或用 install-probe 重新装一遍再卸载。"
    } else {
        Info "没有安装记录，也没有发现探针 jar —— 无事可做。"
    }
    exit 0
}

$manifest = Get-Content -LiteralPath $ManifestFile -Raw | ConvertFrom-Json
$removed = @()

foreach ($entry in @($manifest.files)) {
    $rel = [string]$entry.path
    $full = Join-Path $TargetPath ($rel -replace '/', '\')
    if (-not (Test-Path -LiteralPath $full)) {
        Info "已不存在，跳过：$rel"
        continue
    }
    $actual = (Get-FileHash -LiteralPath $full -Algorithm SHA256).Hash
    if ($actual -ne [string]$entry.sha256) {
        if (-not $Force) {
            Fail "$rel 的哈希与安装记录不一致（装完后被改过）。确认要删加 -Force。"
        }
        Info "哈希不一致，按 -Force 删除：$rel"
    }
    if ($WhatIf) {
        Info "[whatif] 将删除 $rel"
    } else {
        try {
            Remove-Item -LiteralPath $full -Force
            Info "已删除 $rel"
        } catch {
            Fail "删除 $rel 失败（游戏可能正在运行）：$($_.Exception.Message)"
        }
    }
    $removed += $rel
}

if ($manifest.config -and $manifest.config.path) {
    $keys = @($manifest.config.keys)
    if ([bool]$manifest.config.created) {
        if (Test-Path -LiteralPath $ConfigFile) {
            if ($WhatIf) {
                Info "[whatif] 将删除安装时创建的配置文件"
            } else {
                Remove-Item -LiteralPath $ConfigFile -Force
                Info "已删除安装时创建的配置文件"
            }
        }
    } elseif (Test-Path -LiteralPath $ConfigFile) {
        $kept = @()
        foreach ($line in @(Get-Content -LiteralPath $ConfigFile)) {
            $hit = $false
            foreach ($key in $keys) {
                if ($line -match "^\s*$([regex]::Escape($key))\s*[=:]") { $hit = $true; break }
            }
            if (-not $hit) { $kept += $line }
        }
        $hasReal = @($kept | Where-Object { $_.Trim() -ne '' -and -not $_.Trim().StartsWith('#') }).Count -gt 0
        if ($WhatIf) {
            Info "[whatif] 将从配置里移除 $(@($keys) -join ', ')"
        } elseif ($hasReal) {
            [System.IO.File]::WriteAllText($ConfigFile, (($kept -join "`r`n") + "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
            Info "已从配置里移除 $(@($keys) -join ', ')"
        } else {
            Remove-Item -LiteralPath $ConfigFile -Force
            Info "配置里只剩我们写的键，整个文件已删除"
        }
    }
}

if ($WhatIf) {
    Info "[whatif] 将删除安装记录"
} else {
    Remove-Item -LiteralPath $ManifestFile -Force
    Info "已删除安装记录"
}

Write-Host ""
if ($removed.Count -eq 0) {
    Info "完成：没有文件需要删除。"
} else {
    Info "完成：卸掉 $($removed.Count) 个文件（$($removed -join ', ')）。"
}
exit 0
