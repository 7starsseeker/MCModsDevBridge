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

 .PARAMETER Side
    被测 mod 装在哪一侧：server（只服）/ client（只客）/ both（两端，默认）。决定 devrun 起哪几端。
 .PARAMETER Client
    客户端形态：gradle（用本仓 lab 工程的 runClient）/ standalone（真实客户端，给 -ClientExe，
    或给 -ClientRoot + -ClientVersion 让套件按启动器的版本清单起）/ none。

.PARAMETER Proxy
    这个实例访问 GitHub / Maven 用的代理地址（如 http://127.0.0.1:7890），写进登记的 `proxy` 字段。
    之后 devrun 不需要再传 -Proxy。部署向导（setup-wizard.ps1）会主动问一次同样的问题。

.PARAMETER NoProxy
    强制直连，写进登记的 `noProxy: true` —— 即使系统代理开着也不走。

.EXAMPLE
    pwsh -NoProfile -File scripts\register-instance.ps1 -Name fabric -Dir '<MC_ROOT>\Server'

.EXAMPLE
    pwsh -NoProfile -File scripts\register-instance.ps1 -Name pairetest -Dir '<TARGET_DIR>\servers\fabric-pair' -Client none
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Name,
    [string]$Dir,

    [ValidateSet('fabric', 'forge', 'neoforge', 'auto')]
    [string]$Loader = 'auto',

    [int]$GamePort = 0,
    [int]$ProbePort = 0,
    [string]$JavaExe = '',
    [string]$Proxy,
    [switch]$NoProxy,

    [ValidateSet('server', 'client', 'both')]
    [string]$Side = 'both',

    [ValidateSet('gradle', 'standalone', 'none')]
    [string]$Client = 'gradle',
    [string]$ClientProject,
    [string]$ClientExe,
    [string]$ClientRoot,
    [string]$ClientVersion,
    [string]$ClientGameDir,
    [string]$ClientUsername = 'mcdev',
    [int]$ClientProbePort = 39002,
    [ValidateSet('auto', 'on', 'off')][string]$ComputerUse = 'auto',
    [string]$DshHome,

    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'lib\Env.ps1')
$JavaExe = Resolve-JavaExe $JavaExe
$utf8 = New-Object System.Text.UTF8Encoding($false)
if ($Proxy -and $NoProxy) { throw '-Proxy 与 -NoProxy 不能同时给（要么走代理，要么强制直连）。' }

if ($Side -eq 'client') {
    # 纯客户端实例没有服务端目录：-Dir 可省，给了就必须存在（留着当备注）。
    if ($Dir -and -not (Test-Path $Dir)) { throw "服务端目录不存在：$Dir" }
    if ($Dir) { $Dir = (Resolve-Path $Dir).Path }
} else {
    if (-not $Dir) { throw '需要 -Dir <服务端目录>（只有 -Side client 才能省）。' }
    if (-not (Test-Path $Dir)) { throw "服务端目录不存在：$Dir" }
    $Dir = (Resolve-Path $Dir).Path
}

# ---- 加载器探测：看 libraries 布局与启动壳，不猜 ----
function Detect-Loader {
    if (-not $Dir) { return $null }
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
$argsList = if ($Side -eq 'client') { @() } else { Build-Args $Loader }

# ---- 端口：先读 server.properties，读不到再用参数/默认 ----
$props = if ($Dir) { Join-Path $Dir 'server.properties' } else { $null }
$propsData = @{}
if ($props -and (Test-Path $props)) {
    foreach ($line in Get-Content $props -Encoding utf8) {
        if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
        $kv = $line -split '=', 2
        $propsData[$kv[0].Trim()] = $kv[1].Trim()
    }
}
if ($GamePort -le 0 -and $Side -ne 'client') {
    $GamePort = if ($propsData['server-port']) { [int]$propsData['server-port'] } else { 25565 }
}
if ($Side -eq 'client') { $GamePort = 0 }

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
    # 两条路二选一：给 exe（启动器自带命令行），或给 root+version（套件自己按版本清单拼命令行）。
    if ($ClientVersion) {
        if (-not $ClientRoot) { throw "-ClientVersion 需要同时给 -ClientRoot <启动器根目录：含 versions\libraries\assets 的那一层>" }
        if (-not (Test-Path (Join-Path $ClientRoot "versions\$ClientVersion\$ClientVersion.json"))) {
            throw "版本清单不存在：$(Join-Path $ClientRoot "versions\$ClientVersion\$ClientVersion.json")"
        }
        $clientBlock.root = (Resolve-Path $ClientRoot).Path
        $clientBlock.version = $ClientVersion
        if ($ClientGameDir) { $clientBlock.gameDir = $ClientGameDir }
        if ($ClientUsername) { $clientBlock.username = $ClientUsername }
    } elseif ($ClientExe) {
        $clientBlock.exe = $ClientExe
    } else {
        throw "-Client standalone 需要 -ClientExe <启动 exe>，或 -ClientRoot + -ClientVersion"
    }
}

# computer use：DSH 的官方可选插件，不是本套件的能力。auto = 现查本机状态。
$cuState = switch ($ComputerUse) {
    'on' { 'enabled' }
    'off' { 'declined' }
    default { (Get-ComputerUseState $DshHome).state }
}

$cfg = [ordered]@{
    name     = $Name
    loader   = $Loader
    dir      = $Dir
    gamePort = $GamePort
    probePort = $ProbePort
    args     = @($argsList)
    javaExe  = $JavaExe
    side     = $Side
    client   = $clientBlock
    computerUse = $cuState
    notes    = "由 register-instance.ps1 于 $(Get-Date -Format 'yyyy-MM-dd HH:mm') 登记。"
}
# 网络：-Proxy 显式给地址；-NoProxy 强制直连。都不给就留空 —— devrun 时按环境变量/系统代理解析。
if ($Proxy) { $cfg.proxy = $Proxy }
if ($NoProxy) { $cfg.noProxy = $true }

$cfg | ConvertTo-Json -Depth 6 | ForEach-Object { $_ -replace "`r`n", "`n" } |
    Set-Content -Path $target -Encoding utf8 -NoNewline
Add-Content -Path $target -Value '' -Encoding utf8

Write-Host ''
Write-Host "已登记 → $target"
Write-Host "  loader=$Loader  game=$GamePort  probe=$ProbePort  side=$Side  client=$Client"
Write-Host ''
Write-Host '接下来：'
if ($Side -eq 'client') {
    Write-Host "  1) 把探针装进客户端游戏目录：scripts\install-probe.ps1 -Side client -Loader $Loader -Target <gamedir> -Jar <探针 jar>"
    Write-Host "  2) 跑：pwsh -NoProfile -File scripts\devrun.ps1 -Instance $Name -Scenario <场景>"
} else {
    Write-Host "  1) 把探针 jar 放进 $Dir\mods\（或 devrun 时用 -ProbeJar 指定）"
    Write-Host "  2) 跑：pwsh -NoProfile -File scripts\devrun.ps1 -Instance $Name -Scenario scenarios\smoke.json"
}
if ($propsData['white-list'] -eq 'true' -and $propsData['online-mode'] -eq 'false') {
    Write-Host '  3) 注意：online-mode=false + white-list=true 时白名单要写**离线 UUID**（Mojang UUID 对不上，连不进来）'
}
