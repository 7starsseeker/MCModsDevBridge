<#
.SYNOPSIS
    运行时环境解析（java.exe / 代理）—— 本仓不写死任何本机路径或端口。

.DESCRIPTION
    发布物要能在别人的机器上直接跑，所以这两样只能运行时决定：

      · java.exe：显式 -JavaExe → $env:JAVA_HOME\bin\java.exe → PATH 上的 java.exe
      · 代理    ：显式 -Proxy → 实例登记里的 proxy（部署向导问过用户）→ HTTPS_PROXY/HTTP_PROXY
                  → Windows 系统代理（IE 设置）

    「部署时先问用户要不要代理」是刻意设计：能从这里（GitHub）拿到套件的人通常本来就有代理，
    但他的代理端口因机器而异，所以由向导问一次并写进 instances\<name>.json，之后 devrun/打包直接复用。

    端口、实例目录、探针 jar 由 `instances\<name>.json` 提供（register-instance.ps1 /
    setup-wizard.ps1 写；套件本身不携带任何服务端/客户端/加载器文件）。

.EXAMPLE
    # 脚本里（各脚本在 param 块之后 dot-source 一次）
    . (Join-Path $PSScriptRoot 'lib\Env.ps1')
    $java  = Resolve-JavaExe $JavaExe
    $proxy = Resolve-ProxyUrl -Explicit $Proxy -Stored $Cfg.proxy -NoProxy:$NoProxy
    $opts  = Get-JavaProxyOptions $proxy
#>

function Resolve-JavaExe {
    [CmdletBinding()]
    param([string]$Explicit)

    if ($Explicit) {
        if (-not (Test-Path -LiteralPath $Explicit)) { throw "java.exe 不存在：$Explicit（-JavaExe 指错了？）" }
        return (Resolve-Path -LiteralPath $Explicit).Path
    }
    if ($env:JAVA_HOME) {
        $cand = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path -LiteralPath $cand) { return (Resolve-Path -LiteralPath $cand).Path }
    }
    $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw '找不到 java.exe：设置 JAVA_HOME，或用 -JavaExe 指定 java.exe 的完整路径。'
}

function Resolve-JavaHome {
    [CmdletBinding()]
    param([string]$JavaExe)

    $exe = Resolve-JavaExe $JavaExe
    return (Split-Path -Parent (Split-Path -Parent $exe))
}

function Resolve-ProxyUrl {
    [CmdletBinding()]
    param([string]$Explicit, [string]$Stored, [switch]$NoProxy)

    if ($NoProxy) { return $null }
    if ($Explicit) { return $Explicit }
    if ($Stored) { return $Stored }
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

function Get-JavaProxyOptions {
    [CmdletBinding()]
    param([string]$ProxyUrl)

    if (-not $ProxyUrl) { return '' }
    $u = $null
    try { $u = [uri]$ProxyUrl } catch { throw "代理地址解析失败：$ProxyUrl" }
    if (-not $u.Host) { return '' }
    $port = if ($u.Port -gt 0) { $u.Port } else { 80 }
    return ('-Dhttps.proxyHost={0} -Dhttps.proxyPort={1} -Dhttp.proxyHost={0} -Dhttp.proxyPort={1} -Dhttp.nonProxyHosts=localhost|127.0.0.1' -f $u.Host, $port)
}

function Get-ComputerUseState([string]$DshHome) {
<#
.SYNOPSIS
    查这台机器的 DSH 有没有启用 computer use（官方可选插件）。
.DESCRIPTION
    computer use 是 DSH 侧的能力（插件 @deepseek-ai/dsh-computer-use 与它的
    cua-driver MCP provider），不是本套件的一部分。这里只回答「启用了吗」，
    既不安装也不改配置 —— 装不装、要不要在真实桌面上模拟操作，是用户的事。
    检测方式：扫 <DSH_HOME>\profiles\*\cordis.patch.yml 里有没有出现
    dsh-computer-use；该行附近写着 disabled: true 就当作未启用。
    返回 state = enabled | disabled | absent | unknown。
#>
    $dshRoot = if ($DshHome) { $DshHome } elseif ($env:DSH_HOME) { $env:DSH_HOME } else { '' }
    if (-not $dshRoot -or -not (Test-Path -LiteralPath $dshRoot)) {
        return [pscustomobject]@{
            state   = 'unknown'
            detail  = '不知道 DSH home（设 DSH_HOME 环境变量，或传 -DshHome）'
            profiles = @()
        }
    }
    $profilesDir = Join-Path $dshRoot 'profiles'
    if (-not (Test-Path -LiteralPath $profilesDir)) {
        return [pscustomobject]@{
            state   = 'unknown'
            detail  = "没有 profiles 目录：$profilesDir"
            profiles = @()
        }
    }

    $enabled = @()
    $blocked = @()
    foreach ($p in Get-ChildItem -LiteralPath $profilesDir -Directory -ErrorAction SilentlyContinue) {
        $patch = Join-Path $p.FullName 'cordis.patch.yml'
        if (-not (Test-Path -LiteralPath $patch)) { continue }
        $text = Get-Content -LiteralPath $patch -Raw
        if ($text -notmatch 'dsh-computer-use') { continue }
        # 同一行写成 disabled: true，或其后 8 行内出现 disabled: true，都算没启用
        $off = [regex]::IsMatch($text, 'dsh-computer-use[^\r\n]*(?:\r?\n(?:[ \t]+[^\r\n]*){0,7})?\r?\n[ \t]*disabled:\s*true')
        if ($off) { $blocked += $p.Name } else { $enabled += $p.Name }
    }

    if ($enabled.Count -gt 0) {
        return [pscustomobject]@{ state = 'enabled'; detail = 'profile: ' + ($enabled -join ', '); profiles = $enabled }
    }
    if ($blocked.Count -gt 0) {
        return [pscustomobject]@{ state = 'disabled'; detail = '配置在但被 disabled: ' + ($blocked -join ', '); profiles = $blocked }
    }
    return [pscustomobject]@{ state = 'absent'; detail = "没有任何 profile 配置 dsh-computer-use（$profilesDir）"; profiles = @() }
}