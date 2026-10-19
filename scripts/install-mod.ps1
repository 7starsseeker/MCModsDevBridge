#Requires -Version 7.0
<#
.SYNOPSIS
把被测 mod 装进一个游戏目录（服务端或客户端），并留下可以精确卸载的记录。

.DESCRIPTION
`devrun -Mods` 是「跑一次顺便拷进去」，但它不记录、也不负责卸掉；换一个 case 继续跑时残留的 mod
会互相污染（上一轮的 mod 还在，这一轮就不知道是谁在起作用）。这个脚本补的就是那一半：

  · `-Jar A.jar,B.jar`  拷进 `<Target>\mods\`，并把每个文件的 sha256 记进
                        `<Target>\config\mcdev-mods.manifest.json`
  · `-Remove`           只删清单里登记过、且 sha256 对得上的文件（对不上默认停下，`-Force` 才删）
  · `-List`             打印当前清单

它只管「摆文件 + 记账」，不判断 mod 该不该装、也不改任何配置。

.PARAMETER Target
游戏目录（含 `mods\` 的那一层）。服务端是 `server.properties` 所在目录，客户端是 `options.txt` 所在目录。

.PARAMETER Side
`server` 或 `client`。用来做两件事：目标目录像不像对应的一侧（只是警告），以及 jar 自己声明的
`environment` 跟这一侧冲突时提醒（Fabric 的 `fabric.mod.json` 才有这个字段，Forge/NeoForge 没有）。

.PARAMETER Jar
一个或多个 jar 路径。`-Remove` / `-List` 时不用给。

.PARAMETER Remove
卸载：按清单删文件，删完把清单也删掉。没有清单、也没有残留文件时安静退出（exit 0）。

.PARAMETER Force
`-Remove` 时即使 sha256 对不上也删。谨慎：那意味着你手动换过这个文件。

.PARAMETER WhatIf
只打印将要做什么，不落盘。

.EXAMPLE
pwsh -File scripts\install-mod.ps1 -Side server -Target '<TARGET_DIR>\test-server' -Jar '.\build\libs\mymod-1.0.0.jar'

.EXAMPLE
pwsh -File scripts\install-mod.ps1 -Side client -Target '<TARGET_DIR>\client' -Jar .\build\libs\mymod-1.0.0.jar, .\build\libs\mylib-2.0.0.jar

.EXAMPLE
pwsh -File scripts\install-mod.ps1 -Target '<TARGET_DIR>\client' -Remove
#>
param(
    [Parameter(Mandatory = $true)][string]$Target,
    [ValidateSet('server', 'client')][string]$Side = 'server',
    [string[]]$Jar,
    [switch]$Remove,
    [switch]$List,
    [switch]$Force,
    [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'

function Fail([string]$Message) {
    Write-Host "[install-mod] 错误：$Message" -ForegroundColor Red
    exit 1
}

function Info([string]$Message) {
    Write-Host "[install-mod] $Message"
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

# ---------------------------------------------------------------- 目标目录

if (-not (Test-Path -LiteralPath $Target)) {
    Fail "目标目录不存在：$Target"
}
$TargetPath = (Resolve-Path -LiteralPath $Target).Path
$ModsDir = Join-Path $TargetPath 'mods'
$ConfigDir = Join-Path $TargetPath 'config'
$ManifestFile = Join-Path $ConfigDir 'mcdev-mods.manifest.json'

# ---------------------------------------------------------------- 读清单

function Read-Manifest {
    if (-not (Test-Path -LiteralPath $ManifestFile)) {
        return [ordered]@{ tool = 'mcdev-mods'; target = $TargetPath; updatedAt = $null; files = @() }
    }
    $raw = Get-Content -LiteralPath $ManifestFile -Raw
    if (-not $raw) {
        return [ordered]@{ tool = 'mcdev-mods'; target = $TargetPath; updatedAt = $null; files = @() }
    }
    $obj = $raw | ConvertFrom-Json
    $files = @()
    if ($obj.PSObject.Properties['files'] -and $obj.files) { $files = @($obj.files) }
    return [ordered]@{ tool = 'mcdev-mods'; target = $TargetPath; updatedAt = $obj.updatedAt; files = $files }
}

function Write-Manifest($Manifest) {
    if (-not (Test-Path -LiteralPath $ConfigDir)) {
        New-Item -ItemType Directory -Path $ConfigDir -Force | Out-Null
    }
    $Manifest.tool = 'mcdev-mods'
    $Manifest.target = $TargetPath
    $Manifest.updatedAt = (Get-Date).ToString('o')
    $Manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $ManifestFile -Encoding utf8NoBOM
}

$manifest = Read-Manifest

# ---------------------------------------------------------------- -List

if ($List -or (-not $Remove -and -not $Jar)) {
    if (-not $manifest.files -or @($manifest.files).Count -eq 0) {
        Info "没有安装记录（$ManifestFile 不存在或为空）。"
        exit 0
    }
    Info "$TargetPath 里由本脚本装过的 $(@($manifest.files).Count) 个文件："
    foreach ($f in $manifest.files) {
        $full = Join-Path $TargetPath ($f.path -replace '/', '\')
        $state = if (-not (Test-Path -LiteralPath $full)) { 'missing' }
        elseif ((Get-Sha256 $full) -eq $f.sha256) { 'ok' }
        else { 'modified' }
        Write-Host ("  {0,-40} {1,10} B  {2}" -f $f.path, $f.bytes, $state)
    }
    if (-not $List) { Info "（没有给 -Jar，只列清单。装文件请用 -Jar，卸载请用 -Remove。）" }
    exit 0
}

# ---------------------------------------------------------------- -Remove

if ($Remove) {
    if (-not $manifest.files -or @($manifest.files).Count -eq 0) {
        # 没有清单：只提示可能的残留，不动手
        $leftover = @()
        if (Test-Path -LiteralPath $ModsDir) {
            $leftover = @(Get-ChildItem -LiteralPath $ModsDir -Filter *.jar -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -notmatch '^(fabric-api|mcdev_probe)' })
        }
        if ($leftover.Count -eq 0) {
            Info "没有安装记录，也没有可疑残留 —— 无事可做。"
            exit 0
        }
        Info "没有安装记录，但 mods\ 里有 $($leftover.Count) 个不是探针/前置的 jar："
        foreach ($f in $leftover) { Info "  · $($f.Name)（$($f.Length) B）—— 不确定是谁装的，没有动它" }
        exit 0
    }

    $removed = 0
    $kept = 0
    $missing = 0
    foreach ($f in $manifest.files) {
        $full = Join-Path $TargetPath ($f.path -replace '/', '\')
        if (-not (Test-Path -LiteralPath $full)) {
            Info "已不在了：$($f.path)"
            $missing++
            continue
        }
        $sha = Get-Sha256 $full
        if ($sha -ne $f.sha256 -and -not $Force) {
            Info "跳过（sha256 与记录不符，像是被换过）：$($f.path) —— 确认要删就加 -Force"
            $kept++
            continue
        }
        if ($WhatIf) {
            Info "[whatif] 将删除 $($f.path)"
        } else {
            Remove-Item -LiteralPath $full -Force
            Info "已删除 $($f.path)"
        }
        $removed++
    }

    if (-not $WhatIf) {
        if ($kept -gt 0) {
            # 保留的条目继续留在清单里，下次还能看见
            $keepList = @($manifest.files | Where-Object {
                    $full = Join-Path $TargetPath ($_.path -replace '/', '\')
                    (Test-Path -LiteralPath $full) -and ((Get-Sha256 $full) -ne $_.sha256)
                })
            $manifest.files = $keepList
            Write-Manifest $manifest
            Info "清单里还留着 $($keepList.Count) 条（被跳过的那几个）。"
        } else {
            if (Test-Path -LiteralPath $ManifestFile) { Remove-Item -LiteralPath $ManifestFile -Force }
            Info "已删除安装记录 $ManifestFile"
        }
    }

    Info "完成：删除 $removed 个，保留 $kept 个，本来就不在 $missing 个。"
    exit 0
}

# ---------------------------------------------------------------- 安装

if (-not $Jar -or @($Jar).Count -eq 0) {
    Fail "没给 -Jar。装文件请用 -Jar，卸载请用 -Remove，看现状用 -List。"
}
if (-not (Test-Path -LiteralPath $ModsDir)) {
    if ($WhatIf) {
        Info "[whatif] 将创建 $ModsDir"
    } else {
        New-Item -ItemType Directory -Path $ModsDir -Force | Out-Null
        Info "已创建 $ModsDir"
    }
}

$LooksLikeServer = Test-Path -LiteralPath (Join-Path $TargetPath 'server.properties')
$LooksLikeClient = Test-Path -LiteralPath (Join-Path $TargetPath 'options.txt')
if ($Side -eq 'server' -and -not $LooksLikeServer) {
    Info "警告：$TargetPath 里没有 server.properties —— 确认这是服务端目录（-Side server 只是标签，不会改你的目录）。"
}
if ($Side -eq 'client' -and -not $LooksLikeClient) {
    Info "警告：$TargetPath 里没有 options.txt —— 确认这是客户端游戏目录（不是启动器安装目录）。"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue

function Get-JarEnvironment([string]$Path) {
    # 只有 Fabric 的元数据里有 environment 字段；其它加载器返回 $null（不知道）
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $names = @($zip.Entries | ForEach-Object { $_.FullName })
        if ($names -notcontains 'fabric.mod.json') { return $null }
        $entry = $zip.GetEntry('fabric.mod.json')
        $reader = New-Object System.IO.StreamReader($entry.Open())
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($text -match '"environment"\s*:\s*"([^"]+)"') { return $Matches[1] }
        return '*'
    } finally {
        $zip.Dispose()
    }
}

$added = 0
$replaced = 0
$already = 0
$entries = @($manifest.files)

foreach ($j in $Jar) {
    if (-not (Test-Path -LiteralPath $j)) { Fail "找不到 jar：$j" }
    $jarPath = (Resolve-Path -LiteralPath $j).Path
    $jarName = Split-Path -Leaf $jarPath
    if ($jarName -notmatch '\.jar$') { Fail "不是 .jar 文件：$jarPath" }

    $envDecl = Get-JarEnvironment $jarPath
    if ($envDecl -and $envDecl -ne '*' -and $envDecl -ne $Side) {
        Info "警告：$jarName 自己声明 environment=$envDecl，但你正在往 $Side 侧装 —— 装上去也不会加载。"
    }

    $dest = Join-Path $ModsDir $jarName
    $sha = Get-Sha256 $jarPath
    $bytes = (Get-Item -LiteralPath $jarPath).Length
    $wasReplace = $false

    if (Test-Path -LiteralPath $dest) {
        $destSha = Get-Sha256 $dest
        if ($destSha -eq $sha) {
            Info "已在位（内容相同）：mods\$jarName"
            $already++
            $existing = $entries | Where-Object { $_.path -eq "mods/$jarName" }
            if (-not $existing) {
                $entries += [ordered]@{
                    path = "mods/$jarName"; sha256 = $sha; bytes = $bytes
                    addedAt = (Get-Date).ToString('o'); source = $jarPath; replaced = $false
                }
            }
            continue
        }
        $replaced++
        $wasReplace = $true
        Info "覆盖：mods\$jarName（$destSha → $sha）"
    } else {
        $added++
        Info "装：mods\$jarName（$bytes B）"
    }

    if ($WhatIf) {
        Info "[whatif] 将写 $dest"
    } else {
        Copy-Item -LiteralPath $jarPath -Destination $dest -Force
        if (-not (Test-Path -LiteralPath $dest)) { Fail "复制后仍然找不到 $dest" }
        if ((Get-Sha256 $dest) -ne $sha) { Fail "复制后 sha256 不一致：$dest" }
    }

    $entries = @($entries | Where-Object { $_.path -ne "mods/$jarName" })
    $entries += [ordered]@{
        path = "mods/$jarName"; sha256 = $sha; bytes = $bytes
        addedAt = (Get-Date).ToString('o'); source = $jarPath; replaced = $wasReplace
    }
}

if ($WhatIf) {
    Info "[whatif] 将写安装记录 $ManifestFile"
} else {
    $manifest.files = $entries
    Write-Manifest $manifest
    Info "已写安装记录 $ManifestFile"
}

Write-Host ""
Info "完成：新装 $added 个，覆盖 $replaced 个，本来就在 $already 个。"
Info "卸载：pwsh -File scripts\install-mod.ps1 -Target '$TargetPath' -Remove"
exit 0
