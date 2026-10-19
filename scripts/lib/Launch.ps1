<#
.SYNOPSIS
按 Minecraft 的版本清单（version json）拼出一条可直接执行的启动命令行。

.DESCRIPTION
这不是启动器，只做启动器最后一步：把 <root>/versions/<id>/<id>.json 里的
libraries / arguments.jvm / arguments.game 变成 java.exe 的参数。

- 只用清单里已经写好的东西：不下载、不修补、不改 json。清单是启动器（HMCL、
  官方启动器、Prism 等）装好之后留在硬盘上的产物，本身就是完整的。
- 库路径按清单里的相对路径拼在 <root>/libraries 下；规则（rules）按
  当前操作系统/架构求值，用不上的库直接跳过。
- 未定义的占位符会在返回前报出来，而不是悄悄变成空串。
- natives 目录由调用方给一个可写目录；LWJGL 会把自己的 DLL 解压到那里。

.PARAMETER Root
启动器根目录（含 versions\、libraries\、assets\）。

.PARAMETER Version
版本 id（versions\ 下的目录名）。

.PARAMETER GameDir
游戏目录（mods\、config\、saves\ 所在）；可以和 Root 不同。

.PARAMETER NativesDir
natives 解压目录，必须已存在或可创建。

.PARAMETER Username
玩家名（离线登录用）。

.PARAMETER Uuid
玩家 UUID；留空时由用户名派生离线 UUID。

.PARAMETER ExtraJvmArgs
追加到 JVM 参数里的项（探针的 -Dmcdev.probe.* 走这里）。

.EXAMPLE
$c = Build-MinecraftLaunch -Root 'C:\Users\me\.minecraft' -Version '26.3-Fabric' -GameDir '<TARGET_DIR>\mc\client-a' -NativesDir '<TARGET_DIR>\mc\natives'
& $c.JavaExe @($c.Arguments)
#>

function Get-OfflineUuid([string]$Name) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes("OfflinePlayer:$Name")
    $md5 = [System.Security.Cryptography.MD5]::Create()
    try { $hash = $md5.ComputeHash($bytes) } finally { $md5.Dispose() }
    $hash[6] = [byte](($hash[6] -band 0x0F) -bor 0x30)
    $hash[8] = [byte](($hash[8] -band 0x3F) -bor 0x80)
    $hex = ($hash | ForEach-Object { $_.ToString('x2') }) -join ''
    return '{0}-{1}-{2}-{3}-{4}' -f $hex.Substring(0, 8), $hex.Substring(8, 4), $hex.Substring(12, 4), $hex.Substring(16, 4), $hex.Substring(20, 12)
}

function Get-PlatformInfo {
    $arch = if ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -eq 'Arm64') { 'arm64' } else { 'x64' }
    return @{ os = 'windows'; arch = $arch; version = [System.Environment]::OSVersion.Version.ToString() }
}

# 清单里的规则：没有规则 = 放行；有规则时按「最后一条命中的规则」决定。
function Test-RulesMatch($Rules, $Platform, $Features) {
    if (-not $Rules -or @($Rules).Count -eq 0) { return $true }
    $action = 'disallow'
    foreach ($rule in @($Rules)) {
        $match = $true
        if ($rule.os) {
            if ($rule.os.name -and $rule.os.name -ne $Platform.os) { $match = $false }
            if ($rule.os.arch -and $rule.os.arch -ne $Platform.arch) { $match = $false }
        }
        if ($match -and $rule.features) {
            foreach ($prop in $rule.features.PSObject.Properties) {
                if (-not $Features.ContainsKey($prop.Name) -or [bool]$Features[$prop.Name] -ne [bool]$prop.Value) { $match = $false; break }
            }
        }
        if ($match) { $action = $rule.action }
    }
    return ($action -eq 'allow')
}

function Get-VersionManifest([string]$Root, [string]$Version) {
    $json = Join-Path $Root "versions\$Version\$Version.json"
    if (-not (Test-Path -LiteralPath $json)) {
        throw "找不到版本清单：$json（先让启动器把这个版本装好）"
    }
    $obj = Get-Content -LiteralPath $json -Raw | ConvertFrom-Json
    if ($obj.PSObject.Properties['inheritsFrom'] -and $obj.inheritsFrom) {
        throw "版本清单 $Version 依赖 inheritsFrom=$($obj.inheritsFrom)，当前只支持启动器已经合并好的自包含清单。"
    }
    return @{ Json = $obj; Path = $json }
}

function Get-LibraryPath([string]$Root, $Library) {
    $rel = $null
    if ($Library.downloads -and $Library.downloads.artifact -and $Library.downloads.artifact.path) {
        $rel = $Library.downloads.artifact.path
    } else {
        $parts = ([string]$Library.name) -split ':'
        if ($parts.Count -lt 3) { return $null }
        $group = $parts[0] -replace '\.', '\'
        $rel = "$group\$($parts[1])\$($parts[2])\$($parts[1])-$($parts[2])$(if ($parts.Count -ge 4) { "-$($parts[3])" } else { '' }).jar"
    }
    return (Join-Path $Root ("libraries\" + ($rel -replace '/', '\')))
}

function Build-MinecraftLaunch {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Version,
        [Parameter(Mandatory = $true)][string]$GameDir,
        [string]$NativesDir,
        [string]$Username = 'mcdev',
        [string]$Uuid,
        [string[]]$ExtraJvmArgs = @(),
        [string]$JavaExe
    )

    $manifest = Get-VersionManifest $Root $Version
    $json = $manifest.Json
    if (-not $JavaExe) {
        $JavaExe = Resolve-JavaExe ''
    }
    if (-not $Uuid) { $Uuid = Get-OfflineUuid $Username }
    if (-not $NativesDir) { $NativesDir = Join-Path $GameDir 'natives' }
    if (-not (Test-Path -LiteralPath $NativesDir)) { New-Item -ItemType Directory -Path $NativesDir -Force | Out-Null }

    $platform = Get-PlatformInfo
    $features = @{}
    $classpath = New-Object System.Collections.Generic.List[string]
    $missing = New-Object System.Collections.Generic.List[string]
    $nativesJars = New-Object System.Collections.Generic.List[string]

    foreach ($lib in @($json.libraries)) {
        if (-not (Test-RulesMatch $lib.rules $platform $features)) { continue }
        $path = Get-LibraryPath $Root $lib
        if (-not $path) { continue }
        if (-not (Test-Path -LiteralPath $path)) { $missing.Add((Split-Path -Leaf $path)); continue }
        if ([string]$lib.name -match ':natives-') {
            # natives 包也放 classpath：LWJGL 会按 SharedLibraryExtractPath 自己解压。
            $nativesJars.Add($path) | Out-Null
        }
        $classpath.Add($path) | Out-Null
    }

    $clientJar = Join-Path $Root "versions\$Version\$Version.jar"
    if (Test-Path -LiteralPath $clientJar) {
        $classpath.Insert(0, $clientJar)
    } else {
        throw "找不到客户端 jar：$clientJar"
    }

    if ($missing.Count -gt 0) {
        throw ("版本 $Version 的库文件缺失 $($missing.Count) 个（例如 $($missing[0])）——这个版本没装完整，或启动器和这里的 Root 不是同一个。")
    }

    $assetsRoot = Join-Path $Root 'assets'
    $assetIndex = if ($json.assetIndex -and $json.assetIndex.id) { [string]$json.assetIndex.id } else { [string]$json.assets }
    if (-not (Test-Path -LiteralPath (Join-Path $assetsRoot "indexes\$assetIndex.json"))) {
        Write-Warning "assets\indexes\$assetIndex.json 不存在，客户端可能没有声音/语言文件"
    }

    $vars = @{
        '${auth_player_name}'    = $Username
        '${version_name}'        = $Version
        '${game_directory}'      = $GameDir
        '${assets_root}'         = $assetsRoot
        '${assets_index_name}'   = $assetIndex
        '${auth_uuid}'           = $Uuid
        '${auth_access_token}'   = '0'
        '${clientid}'            = ''
        '${auth_xuid}'           = ''
        '${user_type}'           = 'legacy'
        '${version_type}'        = 'release'
        '${natives_directory}'   = $NativesDir
        '${launcher_name}'       = 'mcdev-devrun'
        '${launcher_version}'    = '1'
        '${classpath}'           = ($classpath -join ';')
        # 下面两个是 NeoForge 一类加载器的版本清单会用的（HMCL 合并后的清单里
        # 出现过 -DlibraryDirectory=${library_directory}）：library_directory 就是
        # 启动器的 libraries 根，classpath_separator 在 Windows 上是分号。
        '${library_directory}'   = (Join-Path $Root 'libraries')
        '${classpath_separator}' = ';'
    }

    function Expand-Args($Entries) {
        $out = New-Object System.Collections.Generic.List[string]
        foreach ($entry in @($Entries)) {
            if ($null -eq $entry) { continue }
            if ($entry -is [string]) {
                $value = $entry
                foreach ($key in $vars.Keys) { $value = $value.Replace($key, [string]$vars[$key]) }
                if ($value -match '\$\{[A-Za-z0-9_]+\}') {
                    throw "启动参数里有未定义的占位符：$($Matches[0])（版本清单 $Version 用到了本工具没提供的东西）"
                }
                $out.Add($value) | Out-Null
            } elseif ($entry.value) {
                if (-not (Test-RulesMatch $entry.rules $platform $features)) { continue }
                foreach ($v in @($entry.value)) {
                    $value = [string]$v
                    foreach ($key in $vars.Keys) { $value = $value.Replace($key, [string]$vars[$key]) }
                    if ($value -match '\$\{[A-Za-z0-9_]+\}') {
                        throw "启动参数里有未定义的占位符：$($Matches[0])（版本清单 $Version 用到了本工具没提供的东西）"
                    }
                    $out.Add($value) | Out-Null
                }
            }
        }
        return $out.ToArray()
    }

    $jvm = New-Object System.Collections.Generic.List[string]
    $jvm.Add('-Xmx2G') | Out-Null
    $jvm.Add("-Djava.library.path=$NativesDir\java") | Out-Null
    foreach ($a in (Expand-Args $json.arguments.jvm)) { $jvm.Add($a) | Out-Null }
    foreach ($a in $ExtraJvmArgs) { if ($a) { $jvm.Add($a) | Out-Null } }
    $game = Expand-Args $json.arguments.game

    $mainClass = [string]$json.mainClass
    if (-not $mainClass) { throw "版本清单 $Version 没有 mainClass" }
    $all = @($jvm.ToArray()) + @($mainClass) + @($game)

    return [pscustomobject]@{
        JavaExe    = $JavaExe
        Arguments  = $all
        MainClass  = $mainClass
        Classpath  = $classpath.ToArray()
        GameDir    = $GameDir
        Root       = $Root
        Version    = $Version
        NativesDir = $NativesDir
        Username   = $Username
        Uuid       = $Uuid
    }
}
