<#
.SYNOPSIS
    端到端编排 v1 —— 一条命令跑完「准备 → 起服 → 起客户端 → 连接 → 操作场景 → 取证 → 收尾」。

.DESCRIPTION
    这是 P4 剩余项「DSH 插件层六步编排」的可执行内核。六步的顺序和每一步的失败判据都来自
    前面几段真机踩坑（见 docs/p4a-action-layer-record.md §13.5 / §14.9）：

      1) 准备：把探针 jar（和 -Mods 指定的被测 mod）放进测试服 mods/，清掉旧日志，抢占检查端口
      2) 起服：Start-Process 脱离拉起，轮询 /status 直到就绪（不要用管道接启动脚本的 stdout）
      3) 起客户端：写内层 ps1（JAVA_HOME/代理/-Dmcdev.probe.*），Start-Process 拉起 gradlew runClient
      4) 连接：POST /connect?host&port=...（注意 /connect 只认 query，不认 body），轮询 /status.player
      5) 操作：按 scenario json 逐条打端点，断言 status 与 json 路径
      6) 取证 + 收尾：results.json + summary.md，停客户端与服，检查端口释放

    与 tmp\p4a-*.ps1 的关系：那些是逐个环节的手工脚本（也是本脚本的代码来源），本脚本把它们
    串成一条可复用流水线；两者并存，tmp 的保留作为排障工具。

.PARAMETER Family
    要跑哪一家：fabric / forge / neoforge，或 all（矩阵：三家串行各跑一遍并汇总）。
    all 模式不接受 -EvidenceDir / -KeepRunning：每家要独占端口、各自 finally 收尾。

.PARAMETER Scenario
    场景文件（json）。结构：
        {
          "name": "smoke",
          "steps": [
            { "id": "walk", "target": "client", "method": "POST", "path": "/action/move",
              "body": { "mode": "walk", "durationMs": 400 },
              "expect": { "status": 200, "json": { "moved": true } } }
          ]
        }
    target：server（服务端探针）或 client（客户端探针）。expect.json 用点号路径，值按
    「字符串化后相等」比较（布尔/数字同样适用），这样 `true` 与 `"true"` 不会互相误判。
    expect 还有两种**包含**断言（等值断不了的东西用它）：
        "expect": { "contains": { "data.actionbar": "some text" } }    按点号路径取字段，断言其字符串形式包含子串
        "expect": { "text": ["System chat:", "[Server thread/INFO]"] }   断言原始响应体包含这些子串（日志用）
        "expect": { "textNot": ["server got hello"] }                   断言原始响应体**不**包含这些子串（日志层面的「不该出现」）
    等时序的步骤用 retry（「通过就早退」，比写死 waitMs 稳）：
        "retry": { "times": 20, "intervalMs": 250 }    最多发 20 次、间隔 250ms，任一次断言通过即停；
    waitMs 仍然只在**首次**尝试前生效（用于「上一步的副作用要等一下」）。

    每步可写 `"note": "这条在验什么"`：只进 results.json 与 summary.md 的「说明」列，不影响判定，
    是写给以后读取证的人（和写用例的人）看的。

    判定只来自探针/日志/API 这些机器可读的证据。截图仍然是一个探针端点（`GET /screenshot`），
    但**不进判定链**：什么时机该截、图里算什么对着，跟具体 mod 强相关，硬塞进编排只会得到
    「拍了很多图但没人看」的结果。要留人眼证据就在人测那一步自己截。

.PARAMETER Mods
    额外要塞进测试服 mods/ 的被测 mod jar 路径（可重复给多个）。会先按文件名去重再复制。

.PARAMETER EvidenceDir
    取证输出目录，默认 tmp\devrun-<family>-<yyyyMMdd-HHmmss>。

.PARAMETER KeepRunning
    跑完不收尾（留服和客户端给人工接手排障）。

.EXAMPLE
    pwsh -NoProfile -File scripts\devrun.ps1 -Family fabric -Scenario scenarios\smoke.json

.NOTES
    前置：lab\<family> 已经 build 过（脚本会复制 lab\<family>\build\libs\mcdev_probe-*.jar），
    java.exe 按 -JavaExe → $env:JAVA_HOME → PATH 解析（scripts\lib\Env.ps1）；网络需要代理时由本脚本的
    JAVA_TOOL_OPTIONS 负责（-Proxy / 登记里的 proxy / 环境变量 / Windows 系统代理）。
#>
[CmdletBinding()]
param(
    [ValidateSet('fabric', 'forge', 'neoforge', 'all')]
    [string]$Family = 'fabric',

    # 实例名（instances\<名字>.json）或直接给 json 路径。**发布决策（用户 2026-10-20）**：
    # 套件不带任何客户端/服务器端，实例由使用者自备并登记；-Family 只是「按加载器找同名登记」的简写。
    [string]$Instance,
    # 显式覆盖（套件用户手上没有本仓 lab 工程）：java.exe 完整路径、HTTP 代理。
    # 默认从 JAVA_HOME/PATH 找 java；代理按 -Proxy → 登记 proxy → HTTPS_PROXY/系统代理解析（lib\Env.ps1）。
    [string]$JavaExe,
    [string]$Proxy,
    [switch]$NoProxy,

    # 明确指定探针 jar：套件使用者手上只有 dist 里的 jar、没有 lab 工程时用这个。
    [string]$ProbeJar,

    [string]$Scenario = 'scenarios\smoke.json',

    [string[]]$Mods = @(),

    [string]$EvidenceDir,

    [switch]$KeepRunning,
    # 只解析登记并打印计划（端口/目录/探针 jar/场景要求），**不起服**。安装向导用它自检。
    [switch]$PreflightOnly,

    [int]$ServerTimeoutSec = 240,
    [int]$ClientTimeoutSec = 300,
    [int]$ConnectTimeoutSec = 90
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'lib\Env.ps1')
$Token = 'mcdev'
$Headers = @{ 'X-Mcdev-Token' = $Token }

# ---- 矩阵模式：三家各跑一遍（客户端探针端口三家共用 39002，所以只能串行）----
# 用「重新起一个本脚本进程」而不是函数递归：每家都要独占端口、自己的 finally 收尾，
# 子进程跑挂了也不会污染下一家的状态。聚合结果只认各自 summary.md 里的 SUMMARY 行。
if ($Family -eq 'all') {
    if ($PSBoundParameters.ContainsKey('EvidenceDir')) { throw '-Family all 不接受 -EvidenceDir（每家自建取证目录）' }
    if ($KeepRunning) { throw '-Family all 不接受 -KeepRunning（矩阵跑完必须收尾，否则下一家端口被占）' }
    $matrix = New-Object System.Collections.ArrayList
    $allPass = $true
    foreach ($f in 'fabric', 'forge', 'neoforge') {
        Write-Host ("[{0}] ===== 矩阵 {1} =====" -f (Get-Date -Format 'HH:mm:ss'), $f)
        $childArgs = @('-NoProfile', '-File', $PSCommandPath, '-Family', $f, '-Scenario', $Scenario)
        if ($Mods.Count -gt 0) { $childArgs += @('-Mods', ($Mods -join ',')) }
        & pwsh @childArgs
        $code = $LASTEXITCODE
        $dir = Get-ChildItem (Join-Path $Root 'tmp') -Directory -Filter "devrun-$f-*" -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        $summary = if ($dir) { (Select-String -Path (Join-Path $dir.FullName 'summary.md') -Pattern 'PASS=\d+ FAIL=\d+ total=\d+' -ErrorAction SilentlyContinue |
                Select-Object -First 1).Matches.Value } else { 'n/a' }
        if ($code -ne 0 -or -not $summary -or $summary -notmatch 'FAIL=0') { $allPass = $false }
        $null = $matrix.Add([pscustomobject]@{ family = $f; exit = $code; summary = $summary
                evidence = if ($dir) { $dir.FullName } else { '-' } })
    }
    Write-Host ''
    $matrix | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
    if (-not $allPass) { Write-Host '矩阵结论：有失败'; exit 1 }
    Write-Host '矩阵结论：三家全绿'
    exit 0
}

# ---- 实例解析（发布决策：套件不带客户端/服务器端，实例自备并登记）----------------
# 登记文件：instances\<名字>.json（字段见 instances\fabric.json 与 docs\deployment-topology.md）。
# 解析顺序：-Instance（名字或 json 路径）→ -Family（找 instances\<family>.json）→ 内置默认。
# 内置默认只覆盖本机这三套，作用仅仅是「老的 -Family 命令在没登记时还能跑」；换机器就该登记。
function Read-InstanceRaw([string]$Spec) {
    $candidates = New-Object System.Collections.ArrayList
    if ($Spec -match '[\\/]' -or $Spec -like '*.json') { $null = $candidates.Add($Spec) }
    $null = $candidates.Add((Join-Path $Root "instances\$Spec.json"))
    foreach ($c in $candidates) {
        if (Test-Path $c) { return (Get-Content $c -Raw -Encoding utf8 | ConvertFrom-Json) }
    }
    return $null
}

$Builtin = @{
    fabric   = @{ loader = 'fabric'; dir = $null; gamePort = 25566; probePort = 39011
                  args = @('-Xmx2G', '-jar', 'fabric-26.3.jar', 'nogui') }
    forge    = @{ loader = 'forge'; dir = $null; gamePort = 25567; probePort = 39012
                  args = @('-Xmx2G', '@user_jvm_args.txt',
                           '@libraries/net/minecraftforge/forge/26.3-66.0.13/win_args.txt', 'nogui') }
    neoforge = @{ loader = 'neoforge'; dir = $null; gamePort = 25568; probePort = 39013
                  args = @('-Xmx2G', '@user_jvm_args.txt',
                           '@libraries/net/neoforged/neoforge/26.3.0.88/win_args.txt', 'nogui') }
}

function Normalize-Instance($raw, [string]$fallbackName) {
    $loader = if ($raw.loader) { [string]$raw.loader } else { $fallbackName }
    $side = if ($raw.side) { [string]$raw.side } else { 'both' }
    $client = $raw.client
    # 客户端加载器：默认跟服务端同一个 family；跨族实例用 client.loader 显式声明，
    # 免得「客户端是 forge 但 project/安装提示都按 fabric 走」这种别扭。
    $clientLoader = if ($client -and $client.loader) { [string]$client.loader } else { $loader }
    return [pscustomobject]@{
        name            = if ($raw.name) { [string]$raw.name } else { $fallbackName }
        loader          = $loader
        dir             = [string]$raw.dir
        gamePort        = [int]$raw.gamePort
        probePort       = [int]$raw.probePort
        args            = @($raw.args)
        javaExe         = if ($raw.javaExe) { [string]$raw.javaExe } else { $null }
        probeJar        = if ($raw.probeJar) { [string]$raw.probeJar } else { $null }
        # 该实例覆盖哪一端：client / server / both。场景预检用它判断「这条用例有没有着落」。
        side            = $side
        # 任意启动器：给了 start.exe 就按它起（探针参数走 JAVA_TOOL_OPTIONS），否则退回 javaExe + args。
        startExe        = if ($raw.start -and $raw.start.exe) { [string]$raw.start.exe } else { $null }
        startArgs       = if ($raw.start -and $raw.start.args) { @($raw.start.args | ForEach-Object { [string]$_ }) } else { @() }
        startEnv        = if ($raw.start -and $raw.start.env) { $h = @{}; foreach ($pp in $raw.start.env.PSObject.Properties) { $h[$pp.Name] = [string]$pp.Value }; $h } else { @{} }
        # 客户端三态：gradle（本仓 lab 工程的 runClient）/ standalone（真实客户端实例）/ none（只测服务端）
        # side=server 的实例**没有客户端**：仅服务端环境不该因为「默认值」去起客户端。
        clientMode      = if ($side -eq 'server') { 'none' } elseif ($client -and $client.mode) { [string]$client.mode } else { 'gradle' }
        clientLoader    = $clientLoader
        clientProject   = if ($client -and $client.project) { [string]$client.project } else { "lab\$clientLoader" }
        clientProbePort = if ($client -and $client.probePort) { [int]$client.probePort } else { 39002 }
        clientExe       = if ($client -and $client.exe) { [string]$client.exe } else { $null }
        clientArgs      = if ($client -and $client.args) { @($client.args) } else { @() }
        # client.mode=standalone 的两条路：给 exe 就用它（启动器自带命令行），
        # 给 root+version 就按版本清单自己拼 java 命令行（scripts\lib\Launch.ps1）。
        clientRoot      = if ($client -and $client.root) { [string]$client.root } else { $null }
        clientVersion   = if ($client -and $client.version) { [string]$client.version } else { $null }
        clientGameDir   = if ($client -and $client.gameDir) { [string]$client.gameDir } else { $null }
        clientJavaExe   = if ($client -and $client.javaExe) { [string]$client.javaExe } else { $null }
        clientUsername  = if ($client -and $client.username) { [string]$client.username } else { 'mcdev' }
        # client.mode=external 时等用户自己把客户端起好；默认 10 分钟（人手动操作比脚本慢得多）
        externalWaitSec = if ($client -and $client.waitSec) { [int]$client.waitSec } else { 600 }
    }
}

$Spec = if ($Instance) { [string]$Instance } else { [string]$Family }
$rawCfg = Read-InstanceRaw $Spec
if ($null -eq $rawCfg) {
    $rawCfg = $Builtin[$Spec]
    if ($null -eq $rawCfg) {
        throw "没有实例 '$Spec' 的登记，也没有内置默认。先写 instances\$Spec.json（见 docs\deployment-topology.md），或用 -Instance <json 路径>。"
    }
    Write-Host "（实例 '$Spec' 未登记，用内置默认；换机器请登记 instances\$Spec.json）"
}
$Cfg = Normalize-Instance $rawCfg $Spec
$InstanceName = $Cfg.name
$Family = $Cfg.loader
# 客户端加载器不一定等于服务端：跨族实例（fabric 服 + forge 客）靠 client.loader 声明。
$ClientFamily = $Cfg.clientLoader
$ServerDir = $Cfg.dir
$GamePort = $Cfg.gamePort
$ServerProbe = $Cfg.probePort
$ClientProbe = $Cfg.clientProbePort
$ClientBase = "http://127.0.0.1:$ClientProbe"
$ServerBase = "http://127.0.0.1:$ServerProbe"
# 优先级：-JavaExe > 实例登记 > JAVA_HOME/PATH。全空时 Resolve-JavaExe 报错并说明该设什么。
$JavaExe = Resolve-JavaExe ($(if ($JavaExe) { $JavaExe } elseif ($Cfg.javaExe) { $Cfg.javaExe } else { '' }))
$HasClient = ($Cfg.clientMode -ne 'none')
# side 决定「哪一端在场」：server=只有服（无客户端）、client=只有客（没有服务端可起）、both=两端。
# 客户端永远由套件或用户提供；side=client 时套件不碰服务端，也不往任何服务端目录写东西。
$HasServer = ($Cfg.side -ne 'client')

if ($HasServer -and -not $ServerDir) {
    throw "实例 '$InstanceName' 没有登记服务端目录：先运行 scripts\setup-wizard.ps1，或 register-instance.ps1 -Dir <服务端目录>（套件不携带任何服务端）"
}
if ($HasServer -and -not (Test-Path $ServerDir)) {
    throw "实例 '$InstanceName' 的服务端目录不存在：$ServerDir（先自备一个 $Family 服务端，再登记 instances\$InstanceName.json）"
}
if (-not $HasServer -and -not $HasClient) {
    throw "实例 '$InstanceName' 的 side=client 但又登记 client.mode=none：两端都没有，devrun 无从下手。"
}

if (-not $EvidenceDir) {
    $EvidenceDir = Join-Path $Root ("tmp\devrun-{0}-{1}" -f $InstanceName, (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
# 取证目录必须是绝对路径：gradle 客户端由 run-client.ps1 启动，那个壳会先 Set-Location 到
# lab\<loader>，此时写死的相对路径（`*>` 的重定向目标）会解析到 lab\<loader>\tmp\... ——
# 目录不存在 ⇒ 壳启动即失败、没有任何输出，外层只能干等 300 秒超时。
$EvidenceDir = (Resolve-Path -LiteralPath $EvidenceDir).Path

$Results = New-Object System.Collections.ArrayList
$PhaseTimes = @{}

function Write-Phase([string]$Name) {
    Write-Host ("[{0}] === {1} ===" -f (Get-Date -Format 'HH:mm:ss'), $Name)
}

function Get-PortOwner([int]$Port) {
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $conn) { return $null }
    return $conn.OwningProcess
}

function Stop-PortOwner([int]$Port, [string]$Label) {
    $owner = Get-PortOwner $Port
    if ($null -eq $owner) { return }
    Write-Host "  端口 $Port 被 pid $owner 占用（$Label），先杀掉"
    & taskkill.exe /T /F /PID $owner 2>&1 | Out-Null
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 500
        if ($null -eq (Get-PortOwner $Port)) { return }
    }
    throw "端口 $Port 在 10s 内没有释放（$Label）"
}

function Invoke-Probe([string]$Base, [string]$Method, [string]$Path, $Body) {
    # 非 2xx 也要拿 JSON 信封：探测端点把「响亮失败」放在 4xx/5xx 的 body 里。
    $params = @{ Uri = "$Base$Path"; Method = $Method; Headers = $Headers; SkipHttpErrorCheck = $true; TimeoutSec = 30 }
    if ($null -ne $Body) {
        $params.Body = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Compress -Depth 8 }
        $params.ContentType = 'application/json'
    }
    try {
        $resp = Invoke-WebRequest @params
        return [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $resp.Content }
    } catch {
        return [pscustomobject]@{ Status = 0; Body = ("transport error: " + $_.Exception.Message) }
    }
}

function Wait-Probe([string]$Base, [string]$Label, [int]$TimeoutSec) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $r = Invoke-Probe $Base 'GET' '/status' $null
        if ($r.Status -eq 200) { return $r }
        Start-Sleep -Seconds 2
    }
    throw "$Label 在 ${TimeoutSec}s 内没有就绪（/status 未返回 200）"
}

function Wait-Player([int]$TimeoutSec) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $r = Invoke-Probe $ClientBase 'GET' '/status' $null
        if ($r.Status -eq 200) {
            $j = $r.Body | ConvertFrom-Json
            if ($j.data.player) { return $j.data.player }
        }
        Start-Sleep -Seconds 2
    }
    # 「连不上」的原因几乎总在服务端日志里（白名单、正版验证、mod 不匹配……）。
    # 只报「客户端没进世界」等于让人从头猜，所以把服务端侧最后几条断开原因抄过来。
    $reason = $null
    $serverLog = Join-Path $EvidenceDir 'server.out'
    if (Test-Path $serverLog) {
        $hits = Select-String -Path $serverLog -Pattern 'Disconnecting .*: |lost connection: |Incompatible|not white-listed|Failed to verify' -Encoding utf8 |
            Select-Object -Last 3
        if ($hits) { $reason = ($hits | ForEach-Object { '    ' + $_.Line.Trim() }) -join "`n" }
    }
    $msg = "客户端在 ${TimeoutSec}s 内没有进入世界（/status.player 一直为空）"
    if ($reason) {
        Write-Host ''
        Write-Host '  服务端日志里的原因：'
        Write-Host $reason
    }
    if ($reason -match 'white-list') {
        Write-Host '  提示：服务端开了白名单。离线登录（online-mode=false）时白名单要写**离线 UUID**，不是玩家名。'
    }
    throw $msg
}

# 客户端启动时先闪过的几个一次性界面（图形警告、无障碍向导……）挡在主菜单前面。
# /connect 虽然能从任意界面强推进世界，但那样「用户第一次打开看到什么」就没人验过了。
# 做法：探针读当前界面类型 → 按该界面自己的关闭语义处理（onClose / 点它的继续按钮），
# 不写 options.txt（会改用户自己的游戏设置）、不按坐标盲点、不认识的界面一律不碰。
# 这些界面不是立刻出现的（26.3 实测：先进 GenericMessageScreen，再进向导），所以要等，
# 不能看一眼就下结论；等到主菜单或超时为止。
$BlockingScreens = @{
    'AccessibilityOnboardingScreen'    = 'close'
    'WarningScreen'                    = 'continue'
    'UnsupportedGraphicsWarningScreen' = 'continue'
}
$ContinueLabels = @('continue', 'proceed', 'i understand', 'yes', 'ok', 'done')

function Clear-ClientBlockingScreens([int]$TimeoutSec = 30) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $seen = @{}
    while ((Get-Date) -lt $deadline) {
        $snap = Invoke-Probe $ClientBase 'GET' '/ui/snapshot' $null
        if ($snap.Status -ne 200) { Start-Sleep -Milliseconds 500; continue }
        $data = ($snap.Body | ConvertFrom-Json).data
        $screen = [string]$data.screen
        if (-not $screen) { Start-Sleep -Milliseconds 500; continue }
        if (-not $seen.ContainsKey($screen)) {
            $seen[$screen] = $true
            Write-Host "  当前界面：$screen"
        }
        if (-not $BlockingScreens.ContainsKey($screen)) {
            if ($screen -eq 'TitleScreen') { return }
            Start-Sleep -Milliseconds 500
            continue
        }
        $action = $BlockingScreens[$screen]
        if ($action -eq 'close') {
            $r = Invoke-Probe $ClientBase 'POST' '/ui/close' $null
            $closed = $false
            if ($r.Status -eq 200) { $closed = [bool](($r.Body | ConvertFrom-Json).data.closed) }
            Write-Host "  关掉首启界面 $screen（closed=$closed）"
            if (-not $closed) { return }
        } else {
            $target = $null
            foreach ($w in @($data.widgets)) {
                if (-not $w.active) { continue }
                $label = ([string]$w.label).Trim().ToLowerInvariant()
                if ($ContinueLabels -contains $label) { $target = $w; break }
            }
            if ($null -eq $target) {
                Write-Host "  界面 $screen 上没有可识别的继续按钮，跳过自动处置"
                return
            }
            $cx = [double]$target.x + [double]$target.w / 2
            $cy = [double]$target.y + [double]$target.h / 2
            $r = Invoke-Probe $ClientBase 'POST' "/ui/click?x=$cx&y=$cy" $null
            Write-Host "  界面 $screen → 点 '$($target.label)'（$($r.Status)）"
        }
        Start-Sleep -Milliseconds 800
    }
    Write-Host "  首启界面等待 $TimeoutSec s 后结束（客户端未到主菜单）"
}

function Get-JsonPath($Object, [string]$Path) {
    $cur = $Object
    foreach ($seg in $Path.Split('.')) {
        if ($null -eq $cur) { return $null }
        $prop = $cur.PSObject.Properties[$seg]
        if ($null -eq $prop) { return $null }
        $cur = $prop.Value
    }
    return $cur
}

function Compare-Expect($Body, $Expect) {
    if ($null -eq $Expect) { return @{ Pass = $true; Reason = 'no expect' } }
    $checked = 0
    # expect.text：原始响应体里必须出现的子串（日志、纯文本都在这里断言）。
    # 存在的理由：json 只能做精确等值，而「日志里有一行含某段文字」这种断言没法精确等值。
    if ($null -ne $Expect.text) {
        $raw = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Compress -Depth 8 }
        foreach ($needle in @($Expect.text)) {
            $checked++
            if (-not $raw.Contains([string]$needle)) {
                return @{ Pass = $false; Reason = "text is missing '$needle'" }
            }
        }
    }
    # expect.textNot：原始响应体里**不能**出现的子串（与 text 对称）。
    # 存在的理由：日志层面的「不该出现」和字段层面的 notContains 一样是判据 ——
    # 例如只装了一端的 mod，另一端的日志里就不该出现「握手成功」那几行。
    if ($null -ne $Expect.textNot) {
        $raw = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Compress -Depth 8 }
        foreach ($needle in @($Expect.textNot)) {
            $checked++
            if ($raw.Contains([string]$needle)) {
                return @{ Pass = $false; Reason = "text must not contain '$needle'" }
            }
        }
    }
    # expect.contains：按点号路径取字段，断言该字段的字符串形式**包含**子串。
    # 注意这里**不能**跟着 expect.json 一起 early-return：只写 contains 不写 json 是最常见的用法。
    if ($null -ne $Expect.contains) {
        try { $json = $Body | ConvertFrom-Json } catch { return @{ Pass = $false; Reason = 'body is not JSON' } }
        foreach ($key in $Expect.contains.PSObject.Properties.Name) {
            $checked++
            $needle = [string]$Expect.contains.$key
            $got = Get-JsonPath $json $key
            $gotText = if ($null -eq $got) { '' } else { [string]$got }
            if (-not $gotText.Contains($needle)) {
                return @{ Pass = $false; Reason = "json.$key does not contain '$needle' (got '$gotText')" }
            }
        }
    }
    # expect.notContains：字段的字符串形式**不能**包含子串。用来断言「不该出现的没出现」——
    # 例如服务端装了 mod、客户端没装时，客户端 /mods 的 ids 里不该有那个 mod id。
    # 和 contains 一样，不能跟着 expect.json 一起 early-return。
    if ($null -ne $Expect.notContains) {
        try { $json = $Body | ConvertFrom-Json } catch { return @{ Pass = $false; Reason = 'body is not JSON' } }
        foreach ($key in $Expect.notContains.PSObject.Properties.Name) {
            $checked++
            $needle = [string]$Expect.notContains.$key
            $got = Get-JsonPath $json $key
            $gotText = if ($null -eq $got) { '' } else { [string]$got }
            if ($gotText.Contains($needle)) {
                return @{ Pass = $false; Reason = "json.$key must not contain '$needle' (got '$gotText')" }
            }
        }
    }
    if ($null -eq $Expect.json) { return @{ Pass = $true; Reason = "ok ($checked check(s))" } }
    try { $json = $Body | ConvertFrom-Json } catch { return @{ Pass = $false; Reason = 'body is not JSON' } }
    foreach ($key in $Expect.json.PSObject.Properties.Name) {
        $checked++
        $want = [string]$Expect.json.$key
        $got = Get-JsonPath $json $key
        $gotText = if ($null -eq $got) { '<missing>' } else { [string]$got }
        if ($gotText -ne $want) {
            return @{ Pass = $false; Reason = "json.$key expected '$want' got '$gotText'" }
        }
    }
    return @{ Pass = $true; Reason = "ok ($checked check(s))" }
}

try {
    # ---------------- 步骤 1：准备 ----------------
    $where = if ($HasServer) { "$Family → $ServerDir，游戏 $GamePort，探针 $ServerProbe" } else { "$Family → 仅客户端（无服务端），客户端探针 $ClientProbe" }
    Write-Phase "1/6 准备（$where）"
    $t0 = Get-Date

    # 清掉上一轮残留：端口是最可靠的「有没有东西在跑」的判据，比 pid 文件可靠
    Stop-PortOwner $ServerProbe 'server probe'
    Stop-PortOwner $ClientProbe 'client probe'
    Stop-PortOwner $GamePort 'game port'

    # 探针 jar 来源优先级：-ProbeJar → 实例登记的 probeJar → lab\<loader> 的构建产物。
    # 套件使用者没有 lab 工程，靠前两条；本仓开发者通常走第三条。
    # 统一成「路径字符串」：三个来源的形态不同（参数是 string、登记项是 string、构建产物是 FileInfo），
    # 曾经因为 lab 分支拿来的是 String 却按 FileInfo 读 .FullName ⇒ Copy-Item 报「参数 Path 为 null」。
    $probeJar = $null
    if ($ProbeJar) {
        if (-not (Test-Path $ProbeJar)) { throw "指定的 -ProbeJar 不存在：$ProbeJar" }
        $probeJar = (Resolve-Path $ProbeJar).Path
    } elseif ($Cfg.probeJar -and (Test-Path $Cfg.probeJar)) {
        $probeJar = (Resolve-Path $Cfg.probeJar).Path
    } else {
        $built = Get-ChildItem -Path (Join-Path $Root "lab\$Family\build\libs\*.jar") -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notlike '*sources*' } | Select-Object -First 1
        if ($built) { $probeJar = $built.FullName }
    }
    if (-not $probeJar) {
        throw "找不到探针 jar：用 -ProbeJar <路径> 指定，或在 instances\$InstanceName.json 里写 probeJar，或先跑 lab\$Family 的 build"
    }
    Write-Host "  探针 jar: $(Split-Path -Leaf $probeJar) $((Get-Item $probeJar).Length) B"

    if ($HasServer) {
        $modsDir = Join-Path $ServerDir 'mods'
        New-Item -ItemType Directory -Force -Path $modsDir | Out-Null
        Copy-Item -Path $probeJar -Destination (Join-Path $modsDir 'mcdev_probe-1.0.0.jar') -Force
        foreach ($mod in $Mods) {
            if (-not (Test-Path $mod)) { throw "被测 mod 不存在：$mod" }
            Copy-Item $mod (Join-Path $modsDir (Split-Path -Leaf $mod)) -Force
            Write-Host "  被测 mod: $(Split-Path -Leaf $mod)"
        }
    } elseif ($Mods.Count -gt 0) {
        # side=client 时给 -Mods 没有着落：往哪个客户端的 mods 里放？
        # 客户端目录由 client.gameDir 决定，但装 mod 是 install-probe/装卸脚本的职责，不在这里猜。
        Write-Host "  注意：side=client，-Mods 不会自动装进客户端；请先用 scripts\install-probe.ps1 把被测 mod 放进客户端 mods\"
    }

    $serverOut = Join-Path $EvidenceDir 'server.out'
    $serverErr = Join-Path $EvidenceDir 'server.err'
    $clientOut = Join-Path $EvidenceDir 'client.out'
    # 旧日志必须先删：曾因为轮询读到上一轮同一行而误报「READY after 0s」
    Remove-Item $serverOut, $serverErr, $clientOut -ErrorAction SilentlyContinue

    # 场景预检：先读一遍场景，确认「要客户端」的场景不会在实例没客户端时空跑一半。
    $scenarioPath = if (Test-Path $Scenario) { $Scenario } else { Join-Path $Root $Scenario }
    if (-not (Test-Path $scenarioPath)) { throw "场景文件不存在：$scenarioPath" }
    $sc = Get-Content $scenarioPath -Raw -Encoding utf8 | ConvertFrom-Json
    $clientSteps = @($sc.steps | Where-Object { $_.target -eq 'client' })
    if ($clientSteps.Count -gt 0 -and -not $HasClient) {
        throw "场景 '$($sc.name)' 有 $($clientSteps.Count) 条客户端用例，但实例 '$InstanceName' 登记为 client.mode=none / side=server（没有客户端）。改用服务端场景，或给实例补 client 段。"
    }
    $serverSteps = @($sc.steps | Where-Object { $_.target -eq 'server' })
    if ($serverSteps.Count -gt 0 -and $Cfg.side -eq 'client') {
        throw "场景 '$($sc.name)' 有 $($serverSteps.Count) 条服务端用例，但实例 '$InstanceName' 登记为 side=client。改用客户端场景，或把 side 改成 both。"
    }
    $PhaseTimes['prepare'] = ((Get-Date) - $t0).TotalSeconds

    if ($PreflightOnly) {
        Write-Host ''
        Write-Host '--- 预检结果（未起任何进程）---'
        Write-Host "  实例      : $InstanceName（loader=$Family, side=$($Cfg.side)）"
        Write-Host "  服务端目录: $ServerDir"
        Write-Host "  游戏端口  : $GamePort    探针端口: $ServerProbe"
        Write-Host "  启动方式  : $(if ($Cfg.startExe) { "start.exe = $($Cfg.startExe) " + ($Cfg.startArgs -join ' ') } else { "$JavaExe " + (@('-Dmcdev.probe.port=…') + $Cfg.args -join ' ') })"
        Write-Host "  客户端    : mode=$($Cfg.clientMode) 探针端口=$ClientProbe$(if ($Cfg.clientMode -eq 'standalone') { " exe=$($Cfg.clientExe)" } elseif ($Cfg.clientMode -eq 'gradle') { " project=$($Cfg.clientProject)" })"
        if ($ClientFamily -ne $Family) { Write-Host "  客户端加载器: $ClientFamily（服务端 $Family，跨族组合）" }
        Write-Host "  探针 jar  : $probeJar"
        Write-Host "  场景      : $($sc.name)（$(@($sc.steps).Count) 步：客户端 $($clientSteps.Count) / 服务端 $($serverSteps.Count)）"
        Write-Host '  结论      : 登记可用'
        return
    }

    # ---------------- 步骤 2：起服 ----------------
    if (-not $HasServer) {
        Write-Phase '2/6 跳过服务端（side=client：这次没有服务端可起）'
        $PhaseTimes['server'] = 0
        $serverPid = $null
    } else {
    Write-Phase '2/6 起服务端'
    $t0 = Get-Date
    # 两种起法，取决于实例登记了什么：
    #  a) start.exe：任意启动器/开服器/脚本（.bat/.sh/外壳 jar/HMCL 之类）。我们**不知道**它的命令行
    #     形状，所以探针端口与令牌通过环境变量 JAVA_TOOL_OPTIONS 注入 —— 任何 JVM 启动器都会自己读它。
    #  b) javaExe + args（本仓 lab 工程的形态）：-D 必须写在 -jar / @args **之前**，否则参数落到
    #     Minecraft 参数区，探针就落到默认 39001（第一次跑就是这样：服 8s 起来了、脚本按 39011 等到 240s 超时）。
    $probeJvm = "-Dmcdev.probe.port=$ServerProbe -Dmcdev.probe.token=$Token"
    if ($Cfg.startExe) {
        $env:JAVA_TOOL_OPTIONS = ($env:JAVA_TOOL_OPTIONS + ' ' + $probeJvm).Trim()
        foreach ($k in @($Cfg.startEnv.Keys)) { Set-Item -Path "env:$k" -Value $Cfg.startEnv[$k] }
        Write-Host "  启动器: $($Cfg.startExe) $($Cfg.startArgs -join ' ')"
        $serverPid = (Start-Process -FilePath $Cfg.startExe -ArgumentList @($Cfg.startArgs) `
                -WorkingDirectory $ServerDir -WindowStyle Hidden `
                -RedirectStandardOutput $serverOut -RedirectStandardError $serverErr -PassThru).Id
    } else {
        $javaArgs = @("-Dmcdev.probe.port=$ServerProbe", "-Dmcdev.probe.token=$Token") + $Cfg.args
        $serverPid = (Start-Process -FilePath $JavaExe -ArgumentList $javaArgs `
                -WorkingDirectory $ServerDir -WindowStyle Hidden `
                -RedirectStandardOutput $serverOut -RedirectStandardError $serverErr -PassThru).Id
    }
    Write-Host "  服进程 pid=$serverPid（日志 $serverOut）"
    $serverStatus = Wait-Probe $ServerBase '服务端探针' $ServerTimeoutSec
    $serverJson = $serverStatus.Body | ConvertFrom-Json
    Write-Host "  就绪：loader=$($serverJson.data.loader) probe=$ServerProbe"
    $PhaseTimes['server'] = ((Get-Date) - $t0).TotalSeconds
    }

    # ---------------- 步骤 3：起客户端 ----------------
    # 三态来自实例登记的 client.mode：gradle（本仓 lab 工程的 runClient）/
    # standalone（真实客户端实例，args 里自己带 -Dmcdev.probe.client.port）/ none（只测服务端）。
    if (-not $HasClient) {
        Write-Phase '3/6 跳过客户端（本次实例登记为 client.mode=none）'
        $PhaseTimes['client'] = 0
    } elseif ($Cfg.clientMode -eq 'external') {
        # 用户自备客户端：我们不启动任何东西，只等它的探针上线。发布套件不携带 MC 客户端，
        # 所以新环境里这是默认路径（用户用自己的启动器，mods/ 里放探针 jar，JVM 参数带端口）。
        Write-Phase '3/6 等待外部客户端（client.mode=external：脚本不启动客户端）'
        $t0 = Get-Date
        Write-Host "  请用你自己的启动器启动客户端，并确认："
        Write-Host "    · 客户端 mods\ 里有 mcdev_probe-1.0.0.jar"
        Write-Host "    · JVM 参数带 -Dmcdev.probe.client.port=$ClientProbe 与 -Dmcdev.probe.server.url=http://127.0.0.1:$ServerProbe"
        Write-Host "  （最多等 $($Cfg.externalWaitSec) 秒）"
        Wait-Probe $ClientBase '客户端探针（外部）' $Cfg.externalWaitSec | Out-Null
        Write-Host '  客户端探针已监听'
        $PhaseTimes['client'] = ((Get-Date) - $t0).TotalSeconds
    } elseif ($Cfg.clientMode -eq 'standalone') {
        $t0 = Get-Date
        $probeArgs = @("-Dmcdev.probe.client.port=$ClientProbe",
                       "-Dmcdev.probe.server.url=http://127.0.0.1:$ServerProbe")
        if ($Cfg.clientVersion) {
            # 按启动器留下的版本清单起客户端：套件不下载、不修补，只把清单翻译成命令行。
            # 版本/库/资源来自 client.root，mods/config 来自 client.gameDir —— 两者可以分开，
            # 这样测试客户端不必污染用户自己的游戏目录。
            if (-not $Cfg.clientRoot) { throw "实例 '$InstanceName' 的 client.mode=standalone 用了 version，但没登记 client.root（启动器根目录：含 versions\libraries\assets 的那一层）。" }
            $ClientGameDir = if ($Cfg.clientGameDir) { $Cfg.clientGameDir } else { Join-Path $Cfg.clientRoot "versions\$($Cfg.clientVersion)\game" }
            if (-not (Test-Path -LiteralPath $ClientGameDir)) {
                throw "客户端游戏目录不存在：$ClientGameDir（先建好，或改用 install-probe 装到一个空目录）"
            }
            $clientManifest = Join-Path $ClientGameDir 'config\mcdev-probe.manifest.json'
            if (-not (Test-Path -LiteralPath $clientManifest)) {
                throw "这个客户端目录里没有探针的安装记录：$ClientGameDir`n先运行 scripts\install-probe.ps1 -Side client -Target '$ClientGameDir' -Loader $ClientFamily"
            }
            Write-Phase "3/6 起独立客户端（$($Cfg.clientRoot) 的 $($Cfg.clientVersion) → $ClientGameDir）"
            . (Join-Path $PSScriptRoot 'lib\Launch.ps1')
            $clientJava = if ($Cfg.clientJavaExe) { Resolve-JavaExe $Cfg.clientJavaExe } else { $JavaExe }
            $launch = Build-MinecraftLaunch -Root $Cfg.clientRoot -Version $Cfg.clientVersion `
                -GameDir $ClientGameDir -NativesDir (Join-Path $EvidenceDir 'client-natives') `
                -Username $Cfg.clientUsername -JavaExe $clientJava -ExtraJvmArgs $probeArgs
            $clientPid = (Start-Process -FilePath $launch.JavaExe -ArgumentList $launch.Arguments `
                    -WorkingDirectory $ClientGameDir -WindowStyle Minimized `
                    -RedirectStandardOutput $clientOut -RedirectStandardError ($clientOut + '.err') -PassThru).Id
            Write-Host "  客户端 pid=$clientPid（java $($launch.JavaExe)，日志 $clientOut）"
        } else {
            if (-not $Cfg.clientExe -or -not (Test-Path $Cfg.clientExe)) {
                throw "实例 '$InstanceName' 的 client.mode=standalone，但 client.exe 不存在：$($Cfg.clientExe)（也可以登记 client.root + client.version 让套件按版本清单启动）"
            }
            Write-Phase "3/6 起独立客户端（$($Cfg.clientExe)）"
            $standaloneArgs = $probeArgs + @($Cfg.clientArgs)
            $clientPid = (Start-Process -FilePath $Cfg.clientExe -ArgumentList $standaloneArgs `
                    -WindowStyle Hidden -RedirectStandardOutput $clientOut `
                    -RedirectStandardError ($clientOut + '.err') -PassThru).Id
            Write-Host "  客户端 pid=$clientPid（日志 $clientOut）"
        }
        Wait-Probe $ClientBase '客户端探针' $ClientTimeoutSec | Out-Null
        Write-Host '  客户端探针已监听'
        $PhaseTimes['client'] = ((Get-Date) - $t0).TotalSeconds
    } else {
        Write-Phase "3/6 起客户端（gradlew runClient，$ClientProbe）"
        $t0 = Get-Date
        $JavaHome = Resolve-JavaHome $JavaExe
        $env:JAVA_HOME = $JavaHome
        $env:JAVA_TOOL_OPTIONS = (@(
                (Get-JavaProxyOptions (Resolve-ProxyUrl -Explicit $Proxy -Stored $Cfg.proxy -NoProxy:($NoProxy -or $Cfg.noProxy)))
                "-Dmcdev.probe.server.url=http://127.0.0.1:$ServerProbe"
                "-Dmcdev.probe.client.port=$ClientProbe"
            ) | Where-Object { $_ }) -join ' '
        $inner = Join-Path $EvidenceDir 'run-client.ps1'
        @(
            "`$env:JAVA_HOME='$JavaHome'"
            "`$env:JAVA_TOOL_OPTIONS='$($env:JAVA_TOOL_OPTIONS)'"
            "Set-Location '$Root\\$($Cfg.clientProject)'"
            "& .\gradlew.bat --no-daemon --console=plain runClient *> '$clientOut'"
        ) | Set-Content -Path $inner -Encoding utf8
        $clientWrapper = (Start-Process -FilePath 'pwsh.exe' -ArgumentList @('-NoProfile', '-File', $inner) `
                -WindowStyle Hidden -PassThru).Id
        Write-Host "  客户端 wrapper pid=$clientWrapper（日志 $clientOut）"
        Wait-Probe $ClientBase '客户端探针' $ClientTimeoutSec | Out-Null
        Write-Host '  客户端探针已监听'
        $PhaseTimes['client'] = ((Get-Date) - $t0).TotalSeconds
    }
    $PhaseTimes['client'] = ((Get-Date) - $t0).TotalSeconds

    if ($HasClient) {
        Clear-ClientBlockingScreens
    }

    # ---------------- 步骤 4：连接 ----------------
    if (-not $HasClient) {
        Write-Phase '4/6 跳过连接（无客户端：服务端场景不需要玩家）'
        $player = $null
        $PhaseTimes['connect'] = 0
    } elseif (-not $HasServer) {
        Write-Phase '4/6 跳过连接（side=client：只验客户端本地行为）'
        $player = $null
        $st = Invoke-Probe $ClientBase 'GET' '/status' $null
        $dim = ''
        if ($st.Status -eq 200) { $dim = [string](($st.Body | ConvertFrom-Json).data.dimension) }
        if ($dim) { Write-Host "  客户端已在世界内：dimension=$dim" }
        else { Write-Host '  客户端还没进任何世界（停在界面层）：只适合验证 UI/输入相关能力' }
        $PhaseTimes['connect'] = 0
    } else {
        Write-Phase "4/6 连接 ${GamePort}"
        $t0 = Get-Date
        # /connect 只读 query；用 body 会被静默忽略并连到默认 25566（§14.9 的坑）
        $connect = Invoke-Probe $ClientBase 'POST' "/connect?host=127.0.0.1&port=$GamePort" $null
        if ($connect.Status -ne 200) { throw "connect 失败：$($connect.Status) $($connect.Body)" }
        $player = Wait-Player $ConnectTimeoutSec
        Write-Host "  进入世界：player=$player"

        # 两端加载器各报各的：跨族组合（服务端 fabric + 客户端 forge）在协议层是允许的，
        # 但 mod 必须有「对应加载器的版本」——放错加载器的 jar 加载器只是静默跳过，
        # 不报错也就不影响连接，所以「装没装上」只能靠 /mods 断言。
        $cst = Invoke-Probe $ClientBase 'GET' '/status' $null
        if ($cst.Status -eq 200) {
            $cld = ($cst.Body | ConvertFrom-Json).data
            Write-Host "  客户端加载器：$($cld.loader)（实例声明 $ClientFamily / 服务端 $Family）"
            if ($cld.loader -and $cld.loader -ne $ClientFamily) {
                Write-Host "  注意：客户端探针报的加载器与实例声明的 client.loader（$ClientFamily）不一致 —— 检查 client.version / 装进去的探针 jar"
            }
            if ($cld.loader -and $cld.loader -ne $Family) {
                Write-Host '  注意：两端加载器不同 —— 被测 mod 需要各自加载器的版本，装错的 jar 不会加载（用 /mods 的 contains/notContains 断言）'
            }
        }

        $op = Invoke-Probe $ServerBase 'POST' '/command' (@{ cmd = "op $player" } | ConvertTo-Json -Compress)
        Write-Host "  op $player → $($op.Status)"
        $PhaseTimes['connect'] = ((Get-Date) - $t0).TotalSeconds
    }

    # ---------------- 步骤 5：执行场景 ----------------
    Write-Phase "5/6 执行场景 $Scenario"
    $t0 = Get-Date
    Write-Host "  场景：$($sc.name)（$($sc.steps.Count) 条）"

    $stepIndex = 0
    foreach ($step in $sc.steps) {
        $stepIndex++
        $base = if ($step.target -eq 'server') { $ServerBase } else { $ClientBase }
        $method = if ($step.method) { $step.method } else { 'POST' }
        # {{player}} 占位符：玩家名每次启动都变（Fabric 是 PlayerNNN、Forge/NeoForge 是 Dev），
        # 场景里凡是要引用玩家的地方（tp / op / effect）都写占位符，别把名字写死。
        $path = [string]$step.path
        if ($path -and $path.Contains('{{player}}')) { $path = $path.Replace('{{player}}', $player) }
        $bodyArg = $step.body
        if ($null -ne $bodyArg) {
            $bodyText = if ($bodyArg -is [string]) { $bodyArg } else { $bodyArg | ConvertTo-Json -Compress -Depth 8 }
            $bodyArg = $bodyText.Replace('{{player}}', $player)
        }
        # retry：{ "times": 10, "intervalMs": 300 } —— 同一个请求重复到断言通过或用完次数。
        # 存在的理由：客户端/服务端的可见延迟（聊天回显、actionbar、tick 推进）没有可订阅的完成信号，
        # 只能等；waitMs 是死等固定时长，retry 是「通过就早退」，慢机器上也不必写死大常量。
        $maxAttempts = 1
        $retryInterval = 0
        if ($null -ne $step.retry) {
            if ($null -ne $step.retry.times) { $maxAttempts = [Math]::Max(1, [int]$step.retry.times) }
            if ($null -ne $step.retry.intervalMs) { $retryInterval = [Math]::Max(0, [int]$step.retry.intervalMs) }
        }
        $attempts = 0
        while ($true) {
            $attempts++
            if ($attempts -eq 1 -and $null -ne $step.waitMs) {
                Start-Sleep -Milliseconds ([int]$step.waitMs)   # 仅首次尝试前等（如 tp 之后等落地）；重试间隔走 retry.intervalMs
            }
            $r = Invoke-Probe $base $method $path $bodyArg
            $bodyText = if ($r.Body -is [string]) { $r.Body } else { $r.Body | ConvertTo-Json -Compress -Depth 8 }
            $statusOk = $true
            if ($null -ne $step.expect) {
                if ($null -ne $step.expect.status) { $statusOk = ($r.Status -eq [int]$step.expect.status) }
                elseif ($null -ne $step.expect.statusIn) { $statusOk = ($step.expect.statusIn -contains $r.Status) }
            }
            $jsonCheck = Compare-Expect $r.Body $step.expect
            $pass = $statusOk -and $jsonCheck.Pass
            $reason = if ($statusOk -and $jsonCheck.Pass) { 'ok' } else {
                if (-not $statusOk) { "status expected $($step.expect.status) got $($r.Status)" } else { $jsonCheck.Reason }
            }
            if ($pass -or $attempts -ge $maxAttempts) { break }
            if ($retryInterval -gt 0) { Start-Sleep -Milliseconds $retryInterval }
        }
        if ($pass -and $attempts -gt 1) { $reason = "ok (after $attempts attempts)" }
        $null = $Results.Add([pscustomobject]@{
                id       = $step.id
                target   = $step.target
                request  = "$method $path"
                status   = $r.Status
                attempts = $attempts
                note     = [string]$step.note
                pass     = $pass
                reason   = $reason
                body     = $bodyText
            })
        $flag = if ($pass) { 'PASS' } else { 'FAIL' }
        Write-Host ("  [{0}] {1,-22} {2,4} {3}" -f $flag, $step.id, $r.Status, $reason)
    }
    $PhaseTimes['scenario'] = ((Get-Date) - $t0).TotalSeconds

    # ---------------- 步骤 6：取证 ----------------
    Write-Phase '6/6 取证'
    # side=client 没有服务端在跑：此时 /status 是 transport error，硬 ConvertFrom-Json 会崩在
    # 「Error parsing boolean value」上（body 变成 "transport error: ..."）。
    $fsRaw = Invoke-Probe $ServerBase 'GET' '/status' $null
    $finalServer = if ($HasServer -and $fsRaw.Status -eq 200) { $fsRaw.Body | ConvertFrom-Json } else { $null }
    $finalClient = if ($HasClient) {
        $fcRaw = Invoke-Probe $ClientBase 'GET' '/status' $null
        if ($fcRaw.Status -eq 200) { $fcRaw.Body | ConvertFrom-Json } else { $null }
    } else { $null }
    $serverTail = if (Test-Path $serverOut) { Get-Content $serverOut -Tail 60 } else { @() }
    $clientTail = if (Test-Path $clientOut) { Get-Content $clientOut -Tail 40 } else { @() }

    # 下一步：第一轮自动测试跑完后该干什么。computer use 是 DSH 的官方可选插件
    # （真鼠标 + 真截图），不归本套件管；这里只把「要不要用它」问出口。
    $cuState = if ($Cfg.computerUse) { [string]$Cfg.computerUse } else { (Get-ComputerUseState).state }
    $NextStep = [ordered]@{
        computerUse = $cuState
        ask         = ($cuState -eq 'enabled')
        question    = '第一轮自动测试跑完了：直接手测，还是再用 computer use 跑一轮？'
        options     = @('直接手测', '用 computer use 再跑一轮（真实鼠标 + 真截图）')
        hint        = '截图与真实桌面操作由 DSH 的 computer use 提供，本套件不实现截图。'
        onFailure   = '有失败项时：-KeepRunning 重跑留现场，再用 computer use 进现场看状态/截图找可疑点'
    }

    $resultsPath = Join-Path $EvidenceDir 'results.json'
    [pscustomobject]@{
        family      = $Family
        clientFamily = $ClientFamily
        scenario    = $sc.name
        player      = $player
        gamePort    = $GamePort
        serverProbe = $ServerProbe
        clientProbe = $ClientProbe
        startedAt   = (Get-Date).ToString('s')
        phaseSeconds = $PhaseTimes
        steps       = @($Results)
        server      = if ($finalServer) { $finalServer.data } else { $null }
        client      = if ($finalClient) { $finalClient.data } else { $null }
        nextStep    = $NextStep
    } | ConvertTo-Json -Depth 8 | Set-Content -Path $resultsPath -Encoding utf8

    $passCount = @($Results | Where-Object { $_.pass }).Count
    $failCount = $Results.Count - $passCount
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("# devrun — $Family / $($sc.name)")
    $lines.Add('')
    $lines.Add("- 玩家：``$player``")
    $lines.Add("- 端口：游戏 $GamePort / 服务端探针 $ServerProbe / 客户端探针 $ClientProbe")
    $srvLoader = if ($finalServer) { [string]$finalServer.data.loader } else { '-' }
    $cliLoader = if ($finalClient) { [string]$finalClient.data.loader } else { '-' }
    $lines.Add("- 加载器：服务端 ``$srvLoader`` / 客户端 ``$cliLoader``")
    if ($finalClient -and $finalClient.data.loader -and $finalClient.data.loader -ne $srvLoader) {
        $lines.Add('- 两端加载器不同：被测 mod 需要各自加载器的版本；装错的 jar 会被静默跳过（`/mods` 才是硬证据）。')
    }
    $lines.Add("- 结论：**PASS=$passCount FAIL=$failCount total=$($Results.Count)**")
    $lines.Add('- 阶段耗时（秒）：' + (($PhaseTimes.GetEnumerator() | ForEach-Object { "$($_.Key)=$([math]::Round($_.Value,1))" }) -join ' / '))
    $lines.Add('')
    $lines.Add('## 下一步')
    $lines.Add('')
    if ($NextStep.computerUse -eq 'enabled') {
        $lines.Add('第一轮自动测试跑完了。接下来二选一：① 直接手测；② 再用 DSH 的 computer use 跑一轮（真实鼠标 + 真截图）。')
        $lines.Add('如果这次有失败项：-KeepRunning 重跑留住现场，再用 computer use 进去截图、看可疑点、读现场状态。')
        $lines.Add('截图与真实桌面操作由 DSH 的 computer use 提供，本套件不做截图。')
    } else {
        $lines.Add('第一轮自动测试跑完了，接下来直接手测。本机没启用 DSH 的 computer use；需要真截图就装那个官方可选插件。')
    }
    $lines.Add('')
    $lines.Add('| 用例 | 目标 | 请求 | 状态 | 结论 | 说明 | 理由 |')
    $lines.Add('|---|---|---|---:|---|---|---|')
    foreach ($r in $Results) {
        $verdict = if ($r.pass) { 'PASS' } else { 'FAIL' }
        # 说明列来自场景里的 note 字段（给读结果的人看的），不参与判定。
        $note = ([string]$r.note).Replace('|', '/')
        $lines.Add("| $($r.id) | $($r.target) | ``$($r.request)`` | $($r.status) | $verdict | $note | $($r.reason) |")
    }
    $lines.Add('')
    $lines.Add('## 服务端 /status')
    $lines.Add('```json')
    $lines.Add(($finalServer | ConvertTo-Json -Depth 6))
    $lines.Add('```')
    $lines.Add('## 客户端 /status')
    $lines.Add('```json')
    $lines.Add(($finalClient | ConvertTo-Json -Depth 6))
    $lines.Add('```')
    $lines.Add('## 服务端日志尾巴')
    $lines.Add('```')
    # [string[]]$null 会得到 $null，AddRange($null) 直接抛 —— 无日志/无客户端时就是这条路径。
    if ($serverTail) { $lines.AddRange([string[]]@($serverTail)) }
    $lines.Add('```')
    $lines.Add('## 客户端日志尾巴')
    $lines.Add('```')
    if ($clientTail) { $lines.AddRange([string[]]@($clientTail)) }
    $lines.Add('```')
    $summaryPath = Join-Path $EvidenceDir 'summary.md'
    $lines | Set-Content -Path $summaryPath -Encoding utf8
    Write-Host "  取证：$summaryPath"

    if ($failCount -gt 0) { throw "场景有 $failCount 条 FAIL（见 $summaryPath）" }
}
finally {
    if (-not $KeepRunning) {
        Write-Phase '收尾（停客户端与服务端）'
        $null = Invoke-Probe $ClientBase 'POST' '/quit' $null
        $null = Invoke-Probe $ServerBase 'POST' '/command' '{"cmd":"stop"}'
        $deadline = (Get-Date).AddSeconds(45)
        # 三个端口都要等：客户端 /quit 之后 gradlew 还要几秒才放手 39002，
        # 不等它就会在下一次 devrun 的「准备」阶段被当成旧客户端杀掉（forge 那轮实测就是这样）。
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 1
            if ($null -eq (Get-PortOwner $ServerProbe) -and $null -eq (Get-PortOwner $GamePort) -and $null -eq (Get-PortOwner $ClientProbe)) { break }
        }
        $left = @($ServerProbe, $GamePort, $ClientProbe | Where-Object { $null -ne (Get-PortOwner $_) })
        if ($left.Count -gt 0) { Write-Warning "仍占用的端口：$($left -join ',')" }
        else { Write-Host '  端口已释放' }
    } else {
        Write-Host '  -KeepRunning：保留服务端与客户端'
    }

    if ($NextStep -and -not $PreflightOnly) {
        Write-Host ''
        Write-Host '下一步'
        if ($NextStep.computerUse -eq 'enabled') {
            Write-Host '  1) 直接手测'
            Write-Host '  2) 再用 computer use 跑一轮（真实鼠标 + 真截图；截图由 DSH 侧提供，本套件不实现）'
            Write-Host '  另：这次有失败项时，-KeepRunning 重跑能留住现场，再用 computer use 进去截图、看可疑点、读现场状态。'
        } else {
            Write-Host '  直接手测（本机没启用 DSH 的 computer use；要截图就装那个官方可选插件，然后重跑一次）'
        }
    }
}
