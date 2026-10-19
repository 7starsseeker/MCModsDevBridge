<#
.SYNOPSIS
  部署向导：在新环境里把「用哪个加载器 / MOD 在哪一端 / 现成实例还是新建」问清楚，写进 instances/。

.DESCRIPTION
  这套套件的定位是**辅助 DSH 做 MC mod 开发**，而部署前必须先问清三件事（用户 2026-10-20 定）：

    1) 目标加载器：本套件只支持 **fabric / forge / neoforge**（MC 26.3）。三种可只选一种，也可多选。
    2) 被测 MOD 跑在哪一端：仅客户端 / 仅服务端 / 双端。这决定要准备哪些实例、跑哪些场景
       （客户端探针端口全是 39002，所以**同一时刻只能有一个客户端在跑**）。
    3) 用现成实例还是新准备：现成的只登记路径与端口（自动探测服务端目录、`server.properties` 端口）；
       新准备的建一个空骨架（`mods/`、`eula.txt`、`server.properties`、README），
       服务端程序本身要用户自己放进去 —— 本脚本不下载任何东西。
    4) 网络怎么走：直连 / 走代理 / 用系统代理 —— **部署时问一次，写进登记**。
       能从这里拿到套件的人通常本来就有代理，但代理端口因机器而异，所以不猜、不写死。

  第 4 步写下的值会被 `scripts/lib/Env.ps1` 优先使用：显式 `-Proxy` → 登记里的 `proxy` →
  `HTTPS_PROXY`/`HTTP_PROXY` → Windows 系统代理。登记里写了 `noProxy: true` 则强制直连。

  三种加载器都不沾的环境：本套件帮不上（探针是三个不同 jar，各自只认自己的加载器）。
  作为补偿，实例登记支持 `start.exe`（任意启动器/开服器/脚本）：只要那个启动器最终起的是**被支持的
  加载器**，就能接上；探针端口通过 `JAVA_TOOL_OPTIONS` 注入，不需要知道它的命令行形状。

.PARAMETER NonInteractive
  不提问，全部走参数（适合脚本化/CI）。

.EXAMPLE
  # 交互式（推荐首次使用）
  pwsh -NoProfile -File scripts\setup-wizard.ps1

.EXAMPLE
  # 非交互：登记一台现成的 Fabric 服务端
  pwsh -NoProfile -File scripts\setup-wizard.ps1 -NonInteractive -Name fabric -Loader fabric `
       -Side server -Mode existing -Dir <MC_ROOT>\Server -GamePort 25566 -ProbePort 39011
#>
[CmdletBinding()]
param(
    [string]$Name,
    [ValidateSet('fabric', 'forge', 'neoforge')][string]$Loader,
    [ValidateSet('client', 'server', 'both')][string]$Side,
    [ValidateSet('existing', 'provision')][string]$Mode,
    [string]$Dir,
    [int]$GamePort = 0,
    [int]$ProbePort = 0,
    [string]$JavaExe = '',
    [string[]]$StartArgs = @(),
    [string]$StartExe,
    [string]$ProbeJar,
[ValidateSet('gradle', 'standalone', 'external', 'none')][string]$ClientMode,
[string]$ClientRoot,
[string]$ClientVersion,
[string]$ClientGameDir,
[string]$ClientExe,
[int]$ClientProbePort = 39002,
[string]$Proxy,
    [switch]$NoProxy,
    [switch]$NonInteractive,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'lib\Env.ps1')
$JavaExe = Resolve-JavaExe $JavaExe
$InstanceDir = Join-Path $Root 'instances'
$SuiteVersion = 'mcdev-probe-suite-1.0.0'

function Ask([string]$question, [string[]]$options, [int]$default = 1) {
    Write-Host ''
    Write-Host $question
    for ($i = 0; $i -lt $options.Count; $i++) { Write-Host ("  [{0}] {1}" -f ($i + 1), $options[$i]) }
    while ($true) {
        $a = Read-Host ("选择 1-{0}（默认 {1}）" -f $options.Count, $default)
        if ([string]::IsNullOrWhiteSpace($a)) { return $default }
        $n = 0
        if ([int]::TryParse($a, [ref]$n) -and $n -ge 1 -and $n -le $options.Count) { return $n }
        Write-Host '  输入无效，请重试。'
    }
}

function AskPath([string]$question, [string]$default = '') {
    Write-Host ''
    while ($true) {
        $a = Read-Host ($(if ($default) { "$question（默认 $default）" } else { $question }))
        if ([string]::IsNullOrWhiteSpace($a) -and $default) { return $default }
        if ([string]::IsNullOrWhiteSpace($a)) { Write-Host '  不能为空。'; continue }
        return $a.Trim()
    }
}

function AskPort([string]$question, [int]$default) {
    while ($true) {
        $a = Read-Host ("{0}（默认 {1}）" -f $question, $default)
        if ([string]::IsNullOrWhiteSpace($a)) { return $default }
        $n = 0
        if ([int]::TryParse($a, [ref]$n) -and $n -ge 1024 -and $n -le 65535) { return $n }
        Write-Host '  端口要在 1024-65535。'
    }
}

function Test-LoaderMarkers([string]$path) {
    $hits = @()
    if (Get-ChildItem $path -Filter 'fabric-server*.jar' -ErrorAction SilentlyContinue) { $hits += 'fabric' }
    if (Test-Path (Join-Path $path 'libraries\net\minecraftforge')) { $hits += 'forge' }
    if (Test-Path (Join-Path $path 'libraries\net\neoforged')) { $hits += 'neoforge' }
    if (Test-Path (Join-Path $path '.fabric')) { $hits += 'fabric' }
    return ($hits | Select-Object -Unique)
}

function Get-PropertiesPort([string]$dir) {
    $f = Join-Path $dir 'server.properties'
    if (-not (Test-Path $f)) { return $null }
    foreach ($line in Get-Content $f) {
        if ($line -match '^\s*server-port\s*=\s*(\d+)') { return [int]$Matches[1] }
    }
    return $null
}

Write-Host '════════ 部署向导 ════════'
Write-Host "套件版本：$SuiteVersion；支持范围：**fabric / forge / neoforge × Minecraft 26.3**"
Write-Host '它只做两件事：① 把「哪个加载器、哪一端、哪个实例」问清楚并登记；② 交给 scripts/devrun.ps1 去跑。'
Write-Host '不支持：别的加载器（Quilt/LiteLoader/…）与别的 MC 版本 —— 探针是三个各自只认自家加载器的 jar。'

if (-not $NonInteractive) {
    # ---- 1. 加载器 ----
    $i = Ask '第 1 步：这个环境要开发哪种加载器的 MOD？' @(
        'fabric', 'forge', 'neoforge', '全部三种（三家各登记一份）'
    ) 1
    $Loader = @('fabric', 'forge', 'neoforge', 'all')[$i - 1]

    # ---- 2. 端 ----
    $i = Ask '第 2 步：被测 MOD 跑在哪一端？' @(
        '仅客户端（只要客户端探针）', '仅服务端（只要服务端探针）', '双端（要能连服 + 客户端动作）'
    ) 3
    $Side = @('client', 'server', 'both')[$i - 1]

    # ---- 3. 现成 or 新建 ----
    $i = Ask '第 3 步：用现成实例，还是让向导新建骨架？' @(
        '现成实例（我只登记路径与端口）', '新建骨架（建空目录 + eula + server.properties，服务端程序我自己放）'
    ) 1
    $Mode = @('existing', 'provision')[$i - 1]

    if ($Mode -eq 'existing') { $Dir = AskPath '现成实例的目录（服务端根目录：含 eula.txt / server.properties 的那一层）' }
    else { $Dir = AskPath '新建到哪里（会给一个空骨架）' }

    # ---- 4. 网络（代理）----
    $detected = Resolve-ProxyUrl
    $hint = if ($detected) { "（现在读到的是 $detected）" } else { '（现在没读到系统代理/环境变量）' }
    $i = Ask "第 4 步：这台机器访问 GitHub / Maven 要不要走代理？$hint" @(
        '直连，不走代理（登记里写 noProxy）',
        '走代理，我来填地址',
        '用系统代理 / HTTPS_PROXY（把读到的值写进登记）'
    ) 3
    switch ($i) {
        1 { $NoProxy = $true }
        2 { $Proxy = AskPath '代理地址（如 http://127.0.0.1:7890）' }
        3 {
            if ($detected) { $Proxy = $detected; Write-Host "  将写入登记：$Proxy" }
            else { Write-Warning '没读到系统代理/环境变量 ⇒ 按直连登记（之后可改 instances\<名字>.json 的 proxy 字段）。'; $NoProxy = $true }
        }
    }
}

if (-not $Loader) { throw '缺少 -Loader（fabric|forge|neoforge）' }
if (-not $Side) { throw '缺少 -Side（client|server|both）' }
if (-not $Mode) { throw '缺少 -Mode（existing|provision）' }
if (-not $Dir -and $Side -ne 'client') { throw '缺少 -Dir（-Side client 可以不给：这次没有服务端目录）' }
if ($Proxy -and $NoProxy) { throw '-Proxy 与 -NoProxy 不能同时给（要么走代理，要么强制直连）。' }

if (-not (Test-Path $InstanceDir)) { New-Item -ItemType Directory -Force -Path $InstanceDir | Out-Null }

$loaders = if ($Loader -eq 'all') { @('fabric', 'forge', 'neoforge') } else { @($Loader) }
$written = @()

foreach ($ld in $loaders) {
    $instName = if ($Name) { if ($loaders.Count -gt 1) { "$Name-$ld" } else { $Name } } else { $ld }
    $jsonPath = Join-Path $InstanceDir "$instName.json"
    if ((Test-Path $jsonPath) -and -not $Force) {
        throw "实例已存在：$jsonPath（要覆盖加 -Force，或换个 -Name）"
    }

    # ---- 目录检查 / 建骨架 ----
    if ($Side -eq 'client') {
        # 只做客户端：不碰任何服务端目录，也不建服务端骨架。
        Write-Host '  side=client：跳过服务端目录与骨架（这次没有被测服务端）'
    } elseif ($Mode -eq 'existing') {
        if (-not (Test-Path $Dir)) { throw "目录不存在：$Dir（-Mode existing 必须指向已存在实例）" }
        $markers = Test-LoaderMarkers $Dir
        if ($markers.Count -eq 0) {
            Write-Warning "在 $Dir 里没认出任何加载器标记；仍会登记，但请确认这里确实是 $ld 服务端。"
        } elseif ($markers -notcontains $ld) {
            Write-Warning "检测到标记 $($markers -join ',')，与所选加载器 $ld 不一致 —— 请确认。"
        }
    } else {
        New-Item -ItemType Directory -Force -Path $Dir | Out-Null
        New-Item -ItemType Directory -Force -Path (Join-Path $Dir 'mods') | Out-Null
        if (-not (Test-Path (Join-Path $Dir 'eula.txt'))) {
            Set-Content -Path (Join-Path $Dir 'eula.txt') -Value 'eula=true' -Encoding ascii
        }
        if (-not (Test-Path (Join-Path $Dir 'server.properties'))) {
            $gp = if ($GamePort) { $GamePort } else { 25566 }
            @(
                "# mcdev-probe 骨架（向导生成，可自行修改）",
                "server-port=$gp",
                'online-mode=false',
                'white-list=false',
                'gamemode=creative',
                'level-name=testworld'
            ) | Set-Content -Path (Join-Path $Dir 'server.properties') -Encoding utf8
        }
        Write-Host "  已建骨架：$Dir（mods/、eula.txt、server.properties）"
        Write-Host '  ⚠ 服务端程序本身要你自己放（fabric-server-launch.jar / forge @args / neoforge @args），本脚本不下载。'
    }

    # ---- 端口 ----
    $finalGame = if ($Side -eq 'client') { 0 } elseif ($GamePort) { $GamePort } else {
        $fromProps = Get-PropertiesPort $Dir
        if ($fromProps) { $fromProps } elseif ($NonInteractive) { throw '缺少 -GamePort（目录里也没有 server.properties）' } else { AskPort '游戏端口' 25566 }
    }
    $finalProbe = if ($ProbePort) { $ProbePort } else {
        # 挑一个没被别的实例登记的探针端口（39011 起，按加载器偏移）
        $base = @{ fabric = 39011; forge = 39012; neoforge = 39013 }[$ld]
        if (-not $NonInteractive) { AskPort '探针端口' $base } else { $base }
    }

    # ---- 启动方式 ----
    if (-not $StartExe) {
        if ($Mode -eq 'existing' -and -not $NonInteractive) {
            $i = Ask '这个实例怎么起？' @(
                'java -jar <某个 jar>（我给 java 参数）', '用现成的启动器/脚本（.bat/.sh/外壳 jar）', '先不登记，之后补 start.exe'
            ) 1
            if ($i -eq 2) { $StartExe = AskPath '启动器路径（如 run.bat）' }
            if ($i -eq 3) { $StartArgs = @() }
        }
    }

    # ---- 写登记 ----
    $cfg = [ordered]@{
        name      = $instName
        loader    = $ld
        dir       = $Dir
        gamePort  = $finalGame
        probePort = $finalProbe
        side      = $Side
    }
    if ($Side -eq 'client') {
        # 只做客户端：不登记任何服务端启动信息（dir/javaExe/args 都不写，免得以后有人照抄）。
    } elseif ($StartExe) {
        $cfg.start = [ordered]@{ exe = $StartExe; args = @($StartArgs) }
    } else {
        $cfg.javaExe = $JavaExe
        switch ($ld) {
            'fabric' { $cfg.args = @('-Xmx2G', '-jar', 'fabric-26.3.jar', 'nogui') }
            'forge' { $cfg.args = @('-Xmx2G', '@user_jvm_args.txt', '@libraries/net/minecraftforge/forge/26.3-66.0.13/win_args.txt', 'nogui') }
            'neoforge' { $cfg.args = @('-Xmx2G', '@user_jvm_args.txt', '@libraries/net/neoforged/neoforge/26.3.0.88/win_args.txt', 'nogui') }
        }
    }
    if ($ProbeJar) { $cfg.probeJar = (Resolve-Path $ProbeJar).Path }
    if ($Proxy) { $cfg.proxy = $Proxy }
    if ($NoProxy) { $cfg.noProxy = $true }
    if ($Side -ne 'server') {
        # 客户端三态按登记信息定：
        #  · 给了 -ClientVersion + -ClientRoot → standalone：套件按启动器的版本清单自己拼命令行启动
        #  · 给了 -ClientExe → standalone：交给那个 exe（它的命令行里自己带 -D）
        #  · 本仓有 lab\<loader> 工程 → gradle：改代码即时生效（开发期最常用）
        #  · 都没有 → external：不启动任何东西，只等用户把自己的客户端打开
        # 变量名不能叫 $mode：PowerShell 变量大小写不敏感，$Mode 是 existing/provision。
        $cmode = $ClientMode
        if (-not $cmode) {
            if ($ClientVersion -and $ClientRoot) { $cmode = 'standalone' }
            elseif ($ClientExe) { $cmode = 'standalone' }
            elseif (Test-Path (Join-Path $Root "lab\$ld")) { $cmode = 'gradle' }
            else { $cmode = 'external' }
        }
        $cfg.client = [ordered]@{ mode = $cmode; probePort = $ClientProbePort }
        switch ($cmode) {
            'gradle' { $cfg.client.project = "lab\$ld" }
            'standalone' {
                if ($ClientVersion) {
                    if (-not $ClientRoot) { throw '-ClientVersion 需要同时给 -ClientRoot <启动器根目录：含 versions\libraries\assets 的那一层>' }
                    if (-not (Test-Path (Join-Path $ClientRoot "versions\$ClientVersion\$ClientVersion.json"))) {
                        throw "版本清单不存在：$(Join-Path $ClientRoot "versions\$ClientVersion\$ClientVersion.json")"
                    }
                    $cfg.client.root = (Resolve-Path $ClientRoot).Path
                    $cfg.client.version = $ClientVersion
                    if ($ClientGameDir) { $cfg.client.gameDir = $ClientGameDir }
                } elseif ($ClientExe) {
                    $cfg.client.exe = $ClientExe
                } else {
                    throw '-ClientMode standalone 需要 -ClientRoot + -ClientVersion，或 -ClientExe'
                }
            }
            'external' { $cfg.client.waitSec = 600 }
            'none' { }
        }
        if ($cmode -eq 'external') { Write-Host '  ℹ 客户端登记为 external（用你自己的启动器起，脚本只等探针）' }
        if ($cmode -eq 'standalone' -and $ClientVersion) { Write-Host "  ℹ 客户端登记为 standalone：$ClientRoot 的 $ClientVersion" }
    }
    $cfg.notes = "向导 $(Get-Date -Format 'yyyy-MM-dd HH:mm') 生成；$ld × $Side × $Mode"

    $cfg | ConvertTo-Json -Depth 5 | Set-Content -Path $jsonPath -Encoding utf8
    Write-Host "  已写：$jsonPath"
    $written += $instName
}

Write-Host ''
Write-Host '════════ 完成 ════════'
foreach ($n in $written) {
    Write-Host "实例 '$n'："
    Write-Host "  预检: pwsh -NoProfile -File scripts\devrun.ps1 -Instance $n -Scenario scenarios\smoke.json -PreflightOnly"
    Write-Host "  跑:   pwsh -NoProfile -File scripts\devrun.ps1 -Instance $n -Scenario scenarios\smoke.json"
}
Write-Host ''
Write-Host '提醒：① 同一时刻只能有一个客户端（探针端口都是 39002）；② 客户端探针要装在客户端 mods/ 里；'
Write-Host '      ③ forge/neoforge 的客户端实例需要本来就能启动（缺资源会先下载）；④ 端口被占时 devrun 会先清掉占用者。'
$proxyNote = if ($NoProxy) { '直连（已写 noProxy）' } elseif ($Proxy) { $Proxy } else { '未指定 ⇒ devrun 时按 HTTPS_PROXY / 系统代理解析' }
Write-Host "      ⑤ 网络：$proxyNote（要改就编辑 instances\<名字>.json 的 proxy / noProxy 字段）"
