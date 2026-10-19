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
