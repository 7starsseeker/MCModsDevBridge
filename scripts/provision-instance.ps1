<#
.SYNOPSIS
  为新环境自动「部署」一台服务端实例 —— 三种网络形态都覆盖：直连 / 代理 / 完全离线。

.DESCRIPTION
  边界先说清：**发布套件本身不携带任何 MC 客户端/服务端/加载器**（用户 2026-10-20 定）。
  这个脚本是「部署时替用户去取运行时」的工具，不是套件内容的一部分。

  取哪里：
    · `-CopyFrom <已有服务端目录>`：离线，最快最可控。只拷运行必需的东西，跳过 world/mods/logs。
    · `-Download`：联网取官方安装器（fabric = server launcher jar；forge/neoforge = installer --installServer）。

  网络形态（`-CheckOnly` 先探一遍，别等下载到 90% 才失败）：
    · 直连：什么都不加。
    · 代理：`-Proxy http://127.0.0.1:<PROXY_PORT>`，或自动读 `HTTPS_PROXY`/`HTTP_PROXY`，或读 Windows 系统代理。
      Java 侧（forge/neoforge 安装器）转成 `JAVA_TOOL_OPTIONS` 的 `-Dhttps.proxyHost/Port`，并放行 localhost。
    · 完全离线：`-Offline`，只允许 `-CopyFrom`，一个网络请求都不发。

  国内镜像：fabric 可以 `-Mirror https://bmclapi2.bangbang93.com`（路径与 meta.fabricmc.net 同构）。
  forge/neoforge 的官方安装器没有通用镜像，只能走代理；构建期的 maven 镜像请配 gradle（记录在 docs/deployment-topology.md）。

.EXAMPLE
  # 先探网络（不下载）
  pwsh -NoProfile -File scripts\provision-instance.ps1 -Name probe -Loader fabric -Target <TARGET_DIR> -CheckOnly

 # 离线：从现成服务端复制
  pwsh -NoProfile -File scripts\provision-instance.ps1 -Name dev-a -Loader fabric `
       -Target <MC_ROOT>\Dev-A -CopyFrom <MC_ROOT>\Server -GamePort 25601 -ProbePort 39101

  # 联网 + 显式代理
  pwsh -NoProfile -File scripts\provision-instance.ps1 -Name dev-b -Loader forge `
       -Target <MC_ROOT>\Dev-B -GamePort 25602 -ProbePort 39102 -Download -Proxy http://127.0.0.1:<PROXY_PORT>
#>
[CmdletBinding()]
param(
    [string]$Name = 'probe',
    [ValidateSet('fabric', 'forge', 'neoforge')][string]$Loader = 'fabric',
    [string]$Target = '',
    [string]$CopyFrom,
    [switch]$Download,
    [switch]$Offline,
    [switch]$CheckOnly,
    [string]$Proxy,
    [switch]$NoProxy,
    [string]$Mirror,
    [string]$McVersion = '26.3',
    [string]$LoaderVersion = @{ fabric = '0.20.0'; forge = '66.0.13'; neoforge = '26.3.0.88' },
    [int]$GamePort = 25566,
    [int]$ProbePort = 39011,
    [string]$JavaExe = '',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'lib\Env.ps1')
$JavaExe = Resolve-JavaExe $JavaExe

# ────────────────────────────── 网络形态 ──────────────────────────────
function Resolve-Proxy {
    if ($NoProxy) { return $null }
    if ($Proxy) { return $Proxy }
    foreach ($k in 'HTTPS_PROXY', 'https_proxy', 'HTTP_PROXY', 'http_proxy') {
        $v = [Environment]::GetEnvironmentVariable($k)
        if ($v) { return $v }
    }
    # Windows 系统代理（IE 设置）：只在 ProxyEnable=1 时用
    try {
        $ie = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
        $st = Get-ItemProperty -Path $ie -ErrorAction Stop
        if ($st.ProxyEnable -eq 1 -and $st.ProxyServer) {
            $s = [string]$st.ProxyServer
            if ($s -match '=') { $s = ($s -split ';' | Where-Object { $_ -match '^https=' } | Select-Object -First 1) -replace '^https=', '' }
            if ($s -and $s -notmatch '^[a-z]+://') { $s = "http://$s" }
            return $s
        }
    } catch { }
    return $null
}

$effectiveProxy = Resolve-Proxy
$proxyLabel = if ($NoProxy) { '无（-NoProxy 强制直连）' } elseif ($effectiveProxy) { $effectiveProxy } else { '无（直连）' }

function WebArgs([string]$url) {
    $a = @{ Uri = $url; TimeoutSec = 30; MaximumRedirection = 5 }
    if ($effectiveProxy) { $a.Proxy = $effectiveProxy; $a.ProxyUseDefaultCredentials = $true }
    return $a
}

function Test-Endpoint([string]$url) {
    try {
        $a = WebArgs $url
        $a.Method = 'Head'
        $a.TimeoutSec = 10
        $r = Invoke-WebRequest @a
        return @{ ok = $true; detail = "HTTP $($r.StatusCode)" }
    } catch {
        $msg = $_.Exception.Message
        if ($_.Exception.Response) { return @{ ok = $true; detail = "HTTP $([int]$_.Exception.Response.StatusCode)（主机可达）" } }
        if ($msg -match '407|proxy auth') { return @{ ok = $false; detail = '代理要认证（407）' } }
        if ($msg -match 'RemoteName|not known|Name or service|DNS') { return @{ ok = $false; detail = "DNS 解析失败：$msg" } }
        if ($msg -match 'timed out|Timeout') { return @{ ok = $false; detail = "超时：$msg" } }
        return @{ ok = $false; detail = $msg }
    }
}

$hosts = [ordered]@{
    'meta.fabricmc.net'             = 'Fabric 版本元数据'
    'maven.fabricmc.net'            = 'Fabric loader/API 构件'
    'piston-meta.mojang.com'        = 'Minecraft 版本清单'
    'libraries.minecraft.net'       = 'Minecraft 运行库'
    'resources.download.minecraft.net' = '客户端资源（客户端侧才需要）'
    'maven.minecraftforge.net'      = 'Forge 构件/安装器'
    'maven.neoforged.net'           = 'NeoForge 构件/安装器'
}

Write-Host "网络形态：代理 = $proxyLabel"
if ($Mirror) { Write-Host "镜像：$Mirror（只作用于 fabric 元数据/launcher）" }

if ($CheckOnly) {
    Write-Host ''
    Write-Host '端点探测（HEAD，10s 超时）：'
    $bad = 0
    foreach ($h in $hosts.Keys) {
        $r = Test-Endpoint "https://$h/"
        if (-not $r.ok) { $bad++ }
        '{0,-6} {1,-34} {2}' -f $(if ($r.ok) { 'ok' } else { 'FAIL' }), $h, $r.detail | Write-Host
        if (-not $r.ok) { Write-Host ('      用途：' + $hosts[$h]) }
    }
    Write-Host ''
    if ($bad -eq 0) {
        Write-Host "全部可达（$($hosts.Count) 个端点）。"
        if ($script:Offline) { Write-Host '注意：你已经加了 -Offline，探测结果仅供参考。' }
        exit 0
    }
    Write-Host "$bad/$($hosts.Count) 个端点不可达。"
    Write-Host '  · 有代理：加 -Proxy http://127.0.0.1:<port>（或设 HTTPS_PROXY）再试；'
    Write-Host '  · 完全离线：改用 -CopyFrom <已有服务端目录> -Offline，一个网络请求都不发。'
    exit 1
}

# ────────────────────────────── 前置校验 ──────────────────────────────
if ($Offline -and $Download) { throw '-Offline 与 -Download 互斥：离线部署只能 -CopyFrom' }
if ($Offline -and -not $CopyFrom) { throw '-Offline 需要 -CopyFrom <已有服务端目录>；没有现成运行时只能先联网取一次' }
if (-not $CopyFrom -and -not $Download) { throw '必须二选一：-CopyFrom <已有服务端目录>（离线）或 -Download（联网）' }
if (-not $Target) { throw '-Target <目标目录> 必填（部署到哪里）' }

if ((Test-Path $Target) -and -not $Force) {
    if (Get-ChildItem $Target -Force | Select-Object -First 1) {
        throw "目标目录已存在且非空：$Target（要覆盖加 -Force，或换个 -Target）"
    }
}
New-Item -ItemType Directory -Force -Path $Target | Out-Null
# 转成绝对路径：run.ps1 里会 Set-Location，相对路径换个 cwd 就废了
$Target = (Resolve-Path $Target).Path
if ($CopyFrom) { $CopyFrom = (Resolve-Path $CopyFrom).Path }

function Write-Utf8([string]$path, [string[]]$lines) {
    [System.IO.File]::WriteAllText($path, (($lines -join "`r`n") + "`r`n"), (New-Object System.Text.UTF8Encoding($false)))
}

# ────────────────────────────── 1. 取得服务端本体 ──────────────────────────────
if ($CopyFrom) {
    if (-not (Test-Path $CopyFrom)) { throw "源目录不存在：$CopyFrom" }
    Write-Host "  从 $CopyFrom 复制（跳过 world/mods/logs/存档）…"
    $excl = @('/XD', 'world*', 'mods', 'logs', 'crash-reports', '.mcdev', 'run-data', '/XF', '*.log', '*.pid')
    $rc = (Start-Process -FilePath 'robocopy.exe' -Wait -PassThru -NoNewWindow `
            -ArgumentList (@($CopyFrom, $Target, '/E', '/NFL', '/NDL', '/NJH', '/NJS', '/R:1', '/W:1') + $excl)).ExitCode
    if ($rc -ge 8) { throw "robocopy 失败（exit $rc）" }
    Write-Host "  robocopy exit=$rc（0-7 都算成功）"
} else {
    $metaBase = if ($Mirror) { $Mirror.TrimEnd('/') } else { 'https://meta.fabricmc.net' }
    if ($Loader -eq 'fabric') {
        $lv = $LoaderVersion
        $launcher = '1.0.3'
        $jar = Join-Path $Target "fabric-server-mc.$McVersion-loader.$lv-launcher.$launcher.jar"
        $dl = "$metaBase/v2/versions/loader/$McVersion/$lv/$launcher/server/jar"
        Write-Host "  下载 fabric server launcher → $jar"
        Invoke-WebRequest @(WebArgs $dl + @{ OutFile = $jar; TimeoutSec = 600 }) | Out-Null
    } else {
        $url = switch ($Loader) {
            'forge' { "https://maven.minecraftforge.net/net/minecraftforge/forge/$McVersion-$LoaderVersion/forge-$McVersion-$LoaderVersion-installer.jar" }
            'neoforge' { "https://maven.neoforged.net/releases/net/neoforged/neoforge/$LoaderVersion/neoforge-$LoaderVersion-installer.jar" }
        }
        $jar = Join-Path $Target (Split-Path -Leaf ([uri]$url).AbsolutePath)
        Write-Host "  下载安装器 → $jar"
        Invoke-WebRequest @(WebArgs $url + @{ OutFile = $jar; TimeoutSec = 900 }) | Out-Null
        Write-Host '  运行 installer --installServer …'
        $jopts = @()
        if ($effectiveProxy) {
            $u = [uri]$effectiveProxy
            $jopts = @("-Dhttps.proxyHost=$($u.Host)", "-Dhttps.proxyPort=$($u.Port)",
                        "-Dhttp.proxyHost=$($u.Host)", "-Dhttp.proxyPort=$($u.Port)",
                        '-Dhttp.nonProxyHosts=localhost|127.0.0.1')
            $env:JAVA_TOOL_OPTIONS = (@($env:JAVA_TOOL_OPTIONS) + $jopts | Where-Object { $_ }) -join ' '
            Write-Host "  安装器走代理：$($u.Host):$($u.Port)"
        }
        & $JavaExe @jopts -jar $jar --installServer $Target
        if ($LASTEXITCODE -ne 0) { throw "安装器退出码 $LASTEXITCODE（代理/镜像不通时通常是下载失败，先跑 -CheckOnly）" }
    }
}

# ────────────────────────────── 2. 运行所需的小文件 ──────────────────────────────
New-Item -ItemType Directory -Force -Path (Join-Path $Target 'mods') | Out-Null
if (-not (Test-Path (Join-Path $Target 'eula.txt'))) { Write-Utf8 (Join-Path $Target 'eula.txt') @('eula=true') }

$propsPath = Join-Path $Target 'server.properties'
$props = if (Test-Path $propsPath) { Get-Content $propsPath } else { @() }
$want = [ordered]@{
    'server-port' = "$GamePort"
    'online-mode' = 'false'
    'white-list'  = 'false'
    'gamemode'    = 'creative'
    'level-name'  = 'testworld'
}
$out = New-Object System.Collections.Generic.List[string]
$seen = @{}
foreach ($line in $props) {
    if ($line -match '^\s*([A-Za-z0-9._-]+)\s*=') {
        $k = $Matches[1]
        if ($want.Contains($k)) { $out.Add("$k=$($want[$k])"); $seen[$k] = $true; continue }
    }
    $out.Add($line)
}
foreach ($k in $want.Keys) { if (-not $seen.ContainsKey($k)) { $out.Add("$k=$($want[$k])") } }
Write-Utf8 $propsPath $out.ToArray()

# ────────────────────────────── 3. 启动脚本 ──────────────────────────────
$runPs1 = @(
    '# 由 provision-instance.ps1 生成 —— 统一入口，devrun 可以把它当 start.exe 用。',
    "# 探针端口/令牌走 JAVA_TOOL_OPTIONS（devrun 也会注入）；这里给默认值方便手动启动。",
    "`$env:JAVA_TOOL_OPTIONS = (@(`$env:JAVA_TOOL_OPTIONS, '-Dmcdev.probe.port=$ProbePort', '-Dmcdev.probe.token=mcdev') | Where-Object { `$_ }) -join ' '",
    "`$java = '$JavaExe'",
    "Set-Location '$Target'"
)
if ($Loader -eq 'fabric') {
    $fabricJar = Get-ChildItem $Target -Filter 'fabric-server*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $fabricJar) { throw "目标目录里没有 fabric-server*.jar：$Target（复制源不完整？）" }
    $runPs1 += "& `$java -Xmx2G -jar '$($fabricJar.Name)' nogui"
} elseif ($Loader -eq 'forge') {
    $runPs1 += "& `$java -Xmx2G @user_jvm_args.txt '@libraries/net/minecraftforge/forge/$McVersion-$LoaderVersion/win_args.txt' nogui"
} else {
    $runPs1 += "& `$java -Xmx2G @user_jvm_args.txt '@libraries/net/neoforged/neoforge/$LoaderVersion/win_args.txt' nogui"
}
Write-Utf8 (Join-Path $Target 'run.ps1') $runPs1

# ────────────────────────────── 4. 登记进 instances/ ──────────────────────────────
$instDir = Join-Path $Root 'instances'
New-Item -ItemType Directory -Force -Path $instDir | Out-Null
$cfg = [ordered]@{
    name      = $Name
    loader    = $Loader
    dir       = $Target
    gamePort  = $GamePort
    probePort = $ProbePort
    side      = 'both'
    start     = [ordered]@{ exe = 'pwsh.exe'; args = @('-NoProfile', '-File', (Join-Path $Target 'run.ps1')) }
    client    = [ordered]@{ mode = 'external'; probePort = 39002; waitSec = 600 }
    notes     = "provision-instance.ps1 $(Get-Date -Format 'yyyy-MM-dd HH:mm') 生成（$(if ($CopyFrom) { "copy from $CopyFrom" } else { "downloaded, proxy=$proxyLabel" })）"
}
$json = Join-Path $instDir "$Name.json"
if ((Test-Path $json) -and -not $Force) { throw "实例登记已存在：$json（要覆盖加 -Force）" }
$cfg | ConvertTo-Json -Depth 5 | Set-Content -Path $json -Encoding utf8

Write-Host ''
Write-Host "✅ 已部署：$Target"
Write-Host "   启动脚本：$(Join-Path $Target 'run.ps1')"
Write-Host "   实例登记：$json"
Write-Host "   预检：pwsh -NoProfile -File scripts\devrun.ps1 -Instance $Name -Scenario scenarios\server-only.json -PreflightOnly"
