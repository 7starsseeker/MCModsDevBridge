<#
.SYNOPSIS
    把一个「已经存在于本机」的 MC 服务端实例登记成 instances\<名字>.json，供 devrun.ps1 / DSH 工具使用。

.DESCRIPTION
    **发布决策（用户 2026-10-20）**：套件不带任何客户端/服务端。所以「部署到新环境」的语义是：
      ① 用户自己准备好需要的加载器实例（可能只有一种，看他要开发什么 mod）；
      ② 用本脚本登记一个（或几个）指向那些目录的实例；
      ③ devrun / DSH 工具按名字使用，不再有任何写死的 `<MC_ROOT>\Server*`。

    本脚本只读实例目录、不修改它；唯一写入是 instances\<名字>.json（默认不覆盖已有文件）。

.PARAMETER Name
    实例名（= 登记文件名）。建议就叫加载器名（fabric / forge / neoforge），
    这样 `-Family fabric` 的老命令也能直接命中。

.PARAMETER Dir
    服务端实例根目录（含 `mods/`、`server.properties`、`libraries/` 或 `fabric-server-launch.jar`）。

.PARAMETER Client
    客户端形态：gradle（用本仓 lab 工程的 runClient）/ standalone（真实客户端，需 -ClientExe）/ none。

.EXAMPLE
    pwsh -NoProfile -File scripts\register-instance.ps1 -Name fabric -Dir '<MC_ROOT>\Server'

.EXAMPLE
    pwsh -NoProfile -File scripts\register-instance.ps1 -Name pairetest -Dir '<TARGET_DIR>\servers\fabric-pair' -Client none
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][string]$Dir,

    [ValidateSet('fabric', 'forge', 'neoforge', 'auto')]
    [string]$Loader = 'auto',

    [int]$GamePort = 0,
    [int]$ProbePort = 0,
    [string]$JavaExe = '',

    [ValidateSet('gradle', 'standalone', 'none')]
    [string]$Client = 'gradle',
    [string]$ClientProject,
    [string]$ClientExe,
    [int]$ClientProbePort = 39002,

    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'lib\Env.ps1')
$JavaExe = Resolve-JavaExe $JavaExe
$utf8 = New-Object System.Text.UTF8Encoding($false)

if (-not (Test-Path $Dir)) { throw "服务端目录不存在：$Dir" }
$Dir = (Resolve-Path $Dir).Path

# ---- 加载器探测：看 libraries 布局与启动壳，不猜 ----
function Detect-Loader {
    if (Test-Path (Join-Path $Dir 'libraries\net\neoforged\neoforge')) { return 'neoforge' }
    if (Test-Path (Join-Path $Dir 'libraries\net\minecraftforge\forge')) { return 'forge' }
    if (Test-Path (Join-Path $Dir 'libraries\net\fabricmc')) { return 'fabric' }
    if (Get-ChildItem $Dir -Filter 'fabric-server-launch*.jar' -ErrorAction SilentlyContinue) { return 'fabric' }
    return $null
}

$detected = Detect-Loader
if ($Loader -eq 'auto') {
    if (-not $detected) { throw "无法从目录结构判断加载器：$Dir。请用 -Loader fabric|forge|neoforge 明确指定。" }
    $Loader = $detected
    Write-Host "探测到加载器：$Loader"
} elseif ($detected -and $detected -ne $Loader) {
    Write-Warning "目录结构看起来是 $detected，但你指定的是 $Loader；按你指定的写。"
}

# ---- 启动参数 ----
function Build-Args([string]$loader) {
    switch ($loader) {
        'fabric' {
            $launch = Get-ChildItem $Dir -Filter 'fabric-server-launch*.jar' -ErrorAction SilentlyContinue |
                Select-Object -First 1
            if (-not $launch) {
                $launch = Get-ChildItem $Dir -Filter 'fabric-*.jar' -ErrorAction SilentlyContinue |
                    Where-Object { $_.Name -notlike '*client*' -and $_.Name -notlike '*sources*' } |
                    Select-Object -First 1
            }
            if (-not $launch) { throw "fabric 启动 jar 没找到（fabric-server-launch*.jar / fabric-*.jar）" }
            return @('-Xmx2G', '-jar', $launch.Name, 'nogui')
        }
        'forge' {
            $v = Get-ChildItem (Join-Path $Dir 'libraries\net\minecraftforge\forge') -Directory -ErrorAction SilentlyContinue |
                Select-Object -First 1
            if (-not $v) { throw 'forge 版本目录没找到（libraries\net\minecraftforge\forge\<版本>\）' }
            return @('-Xmx2G', '@user_jvm_args.txt',
                "@libraries/net/minecraftforge/forge/$($v.Name)/win_args.txt", 'nogui')
        }
        'neoforge' {
            $v = Get-ChildItem (Join-Path $Dir 'libraries\net\neoforged\neoforge') -Directory -ErrorAction SilentlyContinue |
                Select-Object -First 1
            if (-not $v) { throw 'neoforge 版本目录没找到（libraries\net\neoforged\neoforge\<版本>\）' }
            return @('-Xmx2G', '@user_jvm_args.txt',
                "@libraries/net/neoforged/neoforge/$($v.Name)/win_args.txt", 'nogui')
        }
    }
}
$argsList = Build-Args $Loader

# ---- 端口：先读 server.properties，读不到再用参数/默认 ----
$props = Join-Path $Dir 'server.properties'
$propsData = @{}
if (Test-Path $props) {
    foreach ($line in Get-Content $props -Encoding utf8) {
        if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
        $kv = $line -split '=', 2
        $propsData[$kv[0].Trim()] = $kv[1].Trim()
    }
}
if ($GamePort -le 0) {
    $GamePort = if ($propsData['server-port']) { [int]$propsData['server-port'] } else { 25565 }
}

# 探针端口：显式给的优先；否则在 39011..39030 里挑一个没被别的实例占用的
$instancesDir = Join-Path $root 'instances'
New-Item -ItemType Directory -Force -Path $instancesDir | Out-Null
$used = @()
foreach ($f in Get-ChildItem $instancesDir -Filter '*.json' -ErrorAction SilentlyContinue) {
    try { $used += [int](Get-Content $f.FullName -Raw -Encoding utf8 | ConvertFrom-Json).probePort } catch { }
}
if ($ProbePort -le 0) {
    $ProbePort = 39011
    while ($used -contains $ProbePort) { $ProbePort++ }
}

$target = Join-Path $instancesDir "$Name.json"
if ((Test-Path $target) -and -not $Force) {
    throw "已存在 $target（要覆盖加 -Force）"
}

$clientBlock = [ordered]@{ mode = $Client; probePort = $ClientProbePort }
if ($Client -eq 'gradle') { $clientBlock.project = if ($ClientProject) { $ClientProject } else { "lab\$Loader" } }
if ($Client -eq 'standalone') {
    if (-not $ClientExe) { throw "-Client standalone 需要 -ClientExe <启动 exe>" }
    $clientBlock.exe = $ClientExe
}

$cfg = [ordered]@{
    name     = $Name
    loader   = $Loader
    dir      = $Dir
    gamePort = $GamePort
    probePort = $ProbePort
    args     = @($argsList)
    javaExe  = $JavaExe
    client   = $clientBlock
    notes    = "由 register-instance.ps1 于 $(Get-Date -Format 'yyyy-MM-dd HH:mm') 登记。"
}

$cfg | ConvertTo-Json -Depth 6 | ForEach-Object { $_ -replace "`r`n", "`n" } |
    Set-Content -Path $target -Encoding utf8 -NoNewline
Add-Content -Path $target -Value '' -Encoding utf8

Write-Host ''
Write-Host "已登记 → $target"
Write-Host "  loader=$Loader  game=$GamePort  probe=$ProbePort  client=$Client"
Write-Host ''
Write-Host '接下来：'
Write-Host "  1) 把探针 jar 放进 $Dir\mods\（或 devrun 时用 -ProbeJar 指定）"
Write-Host "  2) 跑：pwsh -NoProfile -File scripts\devrun.ps1 -Instance $Name -Scenario scenarios\smoke.json"
if ($propsData['white-list'] -eq 'true' -and $propsData['online-mode'] -eq 'false') {
    Write-Host '  3) 注意：online-mode=false + white-list=true 时白名单要写**离线 UUID**（Mojang UUID 对不上，连不进来）'
}
