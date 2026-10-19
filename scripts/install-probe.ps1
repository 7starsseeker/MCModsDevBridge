<#
.SYNOPSIS
把探针装进一个游戏目录（服务端或客户端），并写一份安装记录以便精确卸载。

.DESCRIPTION
只做三件事：
  1. 把与加载器匹配的探针 jar 复制进 <目标>\mods\；
  2. 写/合并 <目标>\config\mcdev-probe.properties（端口、口令、跨端地址）；
  3. 写 <目标>\config\mcdev-probe.manifest.json，记录放了哪些文件、原始哈希、配置键。

为什么要配置文件而不是只靠 -D：真实客户端/服务端由启动器拉起，用户不方便也不应该为了
探针去改启动器 JVM 参数。配置文件的键与 -D 同名，-D 仍可临时覆盖。

本脚本不装被测 mod，也不改加载器本身 —— 那两个是用户自己的事。

.PARAMETER Side
server 或 client。决定写哪个端口键：server 写 mcdev.probe.port，client 写 mcdev.probe.client.port。

.PARAMETER Target
游戏目录（含 mods\ 的那一层；客户端一般是 .minecraft，服务端是含 eula.txt 的那一层）。

.PARAMETER Loader
探针用哪个加载器构建的版本；必须是同族 jar，否则加载器根本不会加载它。

.PARAMETER Jar
显式指定探针 jar；默认取 <仓库>\lab\<Loader>\build\libs\mcdev_probe-<版本>.jar。

.PARAMETER Port
覆盖默认端口（服务端 39001 / 客户端 39002）。同机多实例时才需要。

.PARAMETER WhatIf
只报告将要做什么，不落盘。

.EXAMPLE
pwsh -File scripts\install-probe.ps1 -Side client -Target 'C:\Users\me\.minecraft' -Loader fabric

.EXAMPLE
pwsh -File scripts\install-probe.ps1 -Side server -Target '<TARGET_DIR>\dev\test-server' -Loader fabric -Port 39011
#>
param(
    [Parameter(Mandatory = $true)][ValidateSet('server', 'client')][string]$Side,
    [Parameter(Mandatory = $true)][string]$Target,
    [ValidateSet('fabric', 'forge', 'neoforge')][string]$Loader = 'fabric',
    [string]$Jar,
    [int]$Port = 0,
    [switch]$WhatIf
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$DefaultPort = if ($Side -eq 'server') { 39001 } else { 39002 }
if ($Port -le 0) { $Port = $DefaultPort }
$Token = 'mcdev'

function Fail([string]$Message) {
    Write-Host "[install-probe] 错误：$Message" -ForegroundColor Red
    exit 1
}

function Info([string]$Message) {
    Write-Host "[install-probe] $Message"
}

# ---------------------------------------------------------------- 目标目录

if (-not (Test-Path -LiteralPath $Target)) {
    Fail "目标目录不存在：$Target"
}
$TargetPath = (Resolve-Path -LiteralPath $Target).Path
$ModsDir = Join-Path $TargetPath 'mods'
$ConfigDir = Join-Path $TargetPath 'config'
$ConfigFile = Join-Path $ConfigDir 'mcdev-probe.properties'
$ManifestFile = Join-Path $ConfigDir 'mcdev-probe.manifest.json'

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
    Info "警告：$TargetPath 里没有 server.properties —— 确认这是服务端目录而不是别的什么目录。"
}
if ($Side -eq 'client' -and -not $LooksLikeClient) {
    Info "警告：$TargetPath 里没有 options.txt —— 确认这是客户端游戏目录（不是启动器安装目录）。"
}

# ---------------------------------------------------------------- 探针 jar 与加载器校验

if (-not $Jar) {
    $candidate = Join-Path $Root "lab\$Loader\build\libs\mcdev_probe-1.0.0.jar"
    if (-not (Test-Path -LiteralPath $candidate)) {
        Fail "找不到 $candidate —— 先在 lab\$Loader 下跑 .\gradlew.bat build，或用 -Jar 指定探针 jar。"
    }
    $Jar = $candidate
}
if (-not (Test-Path -LiteralPath $Jar)) {
    Fail "找不到探针 jar：$Jar"
}
$JarPath = (Resolve-Path -LiteralPath $Jar).Path
$JarName = Split-Path -Leaf $JarPath

Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
$zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
try {
    $names = @($zip.Entries | ForEach-Object { $_.FullName })
    $jarFamily = $null
    $descriptor = $null
    if ($names -contains 'fabric.mod.json') {
        $jarFamily = 'fabric'
        $descriptor = 'fabric.mod.json'
        $entry = $zip.GetEntry('fabric.mod.json')
        $reader = New-Object System.IO.StreamReader($entry.Open())
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($text -notmatch '"id"\s*:\s*"mcdev_probe"') {
            Fail "$JarName 的 fabric.mod.json 里没有 mcdev_probe —— 这不是探针 jar。"
        }
    } elseif ($names -contains 'META-INF/neoforge.mods.toml') {
        $jarFamily = 'neoforge'
        $descriptor = 'META-INF/neoforge.mods.toml'
        $entry = $zip.GetEntry('META-INF/neoforge.mods.toml')
        $reader = New-Object System.IO.StreamReader($entry.Open())
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($text -notmatch 'modId\s*=\s*"mcdev_probe"') {
            Fail "$JarName 的 neoforge.mods.toml 里没有 mcdev_probe —— 这不是探针 jar。"
        }
    } elseif ($names -contains 'META-INF/mods.toml') {
        $jarFamily = 'forge'
        $descriptor = 'META-INF/mods.toml'
        $entry = $zip.GetEntry('META-INF/mods.toml')
        $reader = New-Object System.IO.StreamReader($entry.Open())
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($text -notmatch 'modId\s*=\s*"mcdev_probe"') {
            Fail "$JarName 的 mods.toml 里没有 mcdev_probe —— 这不是探针 jar。"
        }
    } else {
        Fail "$JarName 里没有加载器元数据（fabric.mod.json / mods.toml / neoforge.mods.toml）。"
    }
} finally {
    $zip.Dispose()
}

if ($jarFamily -ne $Loader) {
    Fail "$JarName 是 $jarFamily 版的探针，但 -Loader 指定的是 $Loader —— 换了加载器它根本不会被加载。"
}
Info "探针 jar：$JarName（$jarFamily，$([math]::Round((Get-Item -LiteralPath $JarPath).Length / 1024, 1)) KB）"

# ---------------------------------------------------------------- 依赖校验（只查能查的）

if ($Side -eq 'client' -and $Loader -eq 'fabric') {
    $api = @(Get-ChildItem -LiteralPath $ModsDir -Filter 'fabric-api*.jar' -ErrorAction SilentlyContinue)
    if ($api.Count -eq 0) {
        Fail "客户端 mods\ 里没有 fabric-api —— 探针在 fabric.mod.json 里声明了 depends fabric-api，缺了它加载器会直接拒绝加载。先放进 fabric-api 再装探针。"
    }
    Info "依赖 fabric-api：$(Split-Path -Leaf $api[0].FullName)"
}

# ---------------------------------------------------------------- 落盘

$destJar = Join-Path $ModsDir $JarName
$sha = (Get-FileHash -LiteralPath $JarPath -Algorithm SHA256).Hash

if ($WhatIf) {
    Info "[whatif] 将复制 $JarName → $ModsDir"
    Info "[whatif] 将写入 $ConfigFile（端口 $Port，口令 $Token）"
    Info "[whatif] 将写入 $ManifestFile"
    exit 0
}

Copy-Item -LiteralPath $JarPath -Destination $destJar -Force
Info "已复制 → $destJar"

# 配置合并：保留别人的键，只改我们这几个
if (-not (Test-Path -LiteralPath $ConfigDir)) {
    New-Item -ItemType Directory -Path $ConfigDir -Force | Out-Null
}
$portKey = if ($Side -eq 'server') { 'mcdev.probe.port' } else { 'mcdev.probe.client.port' }
$wanted = [ordered]@{}
$wanted[$portKey] = "$Port"
if ($Side -eq 'client') { $wanted['mcdev.probe.server.url'] = "http://127.0.0.1:39001" }
$wanted['mcdev.probe.token'] = $Token

$created = -not (Test-Path -LiteralPath $ConfigFile)
$lines = @()
if (-not $created) {
    $lines = @(Get-Content -LiteralPath $ConfigFile)
}
foreach ($key in $wanted.Keys) {
    $value = $wanted[$key]
    $idx = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match "^\s*$([regex]::Escape($key))\s*[=:]") { $idx = $i; break }
    }
    if ($idx -ge 0) {
        $lines[$idx] = "$key=$value"
    } else {
        $lines += "$key=$value"
    }
}
[System.IO.File]::WriteAllText($ConfigFile, (($lines -join "`r`n") + "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
Info "已写配置 $ConfigFile（$($wanted.Keys -join ', ')）"

$manifest = [ordered]@{
    tool         = 'mcdev-probe'
    probeVersion = ($JarName -replace '^mcdev_probe-', '' -replace '\.jar$', '')
    side         = $Side
    loader       = $Loader
    port         = $Port
    installedAt  = (Get-Date).ToString('o')
    source       = $JarPath
    files        = @(
        [ordered]@{
            path   = "mods/$JarName"
            sha256 = $sha
            bytes  = (Get-Item -LiteralPath $destJar).Length
        }
    )
    config       = [ordered]@{
        path    = 'config/mcdev-probe.properties'
        created = $created
        keys    = @($wanted.Keys)
    }
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $ManifestFile -Encoding utf8NoBOM
Info "已写安装记录 $ManifestFile"

Write-Host ""
Info "完成。下一步："
Info "  · 把这个目录登记成实例（scripts\register-instance.ps1）或在向导里选它；"
Info "  · 卸载：scripts\remove-probe.ps1 -Target '$TargetPath'"
exit 0
