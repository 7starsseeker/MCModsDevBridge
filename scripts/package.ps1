<#
.SYNOPSIS
    P5 打包 v2 —— 三家全量重建 → 核对 → 组装**一个套件**（一个 zip / 一个版本号 / 一份清单）。

.DESCRIPTION
    发布物是「一个套件」，不是三个各自发布的 jar。三个加载器 jar 仍在包里
    （入口不同，无法合并成一个 jar），但用户只跟一个东西打交道：解开 → 跑 install.ps1 → 选加载器。

    步骤（每步都有可验证产出）：
      1) 重建：每家 `gradlew --no-daemon --rerun-tasks build`（Forge 增量构建会漏类，必须全量）
      2) 核对：javac 警告必须为 0；`.class` 数等于基线（fabric 19 / forge 19 / neoforge 21）；jar 内有 mod 元数据
      3) 组装：dist/mcdev-probe-suite-<version>/
             mods/            三份 jar
             dsh-plugin/      DSH 插件源码（mcdev-devrun）
             scripts/         install.ps1 / install-dsh-plugin.ps1 / devrun.ps1 / package.ps1
             scenarios/       smoke.json / actions.json
             README.md LICENSE SHA256SUMS.txt manifest.json
      4) 打包：dist/mcdev-probe-suite-<version>.zip（**这就是挂 GitHub Release 的那一个附件**）
      5) 复验：zip 里必须有三份 jar + install.ps1 + 插件 dsh.mjs + 场景；逐文件校验和全对

    任何一项不过就 exit 1 —— 打出「看起来成功」的包比不打包更糟。

.PARAMETER OutDir
    输出根目录，默认 <repo>/dist。

.PARAMETER SkipBuild
    跳过重建（复用 build/libs 现有 jar），只做核对与打包。

.PARAMETER Force
    套件目录 / zip 已存在时覆盖（默认报错，避免悄悄覆盖已验证的产物）。

.EXAMPLE
    pwsh -NoProfile -File scripts\package.ps1
    pwsh -NoProfile -File scripts\package.ps1 -SkipBuild -Force

.NOTES
    类数基线见 docs/STATE.md「三套骨架的现状」；改了源码结构要同步改 $ExpectClasses。
    java.exe 按 -JavaExe → JAVA_HOME → PATH 解析（scripts\lib\Env.ps1）；需要代理时传 -Proxy，
    否则读 HTTPS_PROXY / Windows 系统代理。
#>
[CmdletBinding()]
param(
    [string]$OutDir,
    [switch]$SkipBuild,
    [string]$JavaExe,
    [string]$Proxy,
    [switch]$NoProxy,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) { $OutDir = Join-Path $Root 'dist' }

. (Join-Path $PSScriptRoot 'lib\Env.ps1')
$env:JAVA_HOME = Resolve-JavaHome $JavaExe
$javaProxy = Get-JavaProxyOptions (Resolve-ProxyUrl -Explicit $Proxy -NoProxy:$NoProxy)
if ($javaProxy) { $env:JAVA_TOOL_OPTIONS = $javaProxy }

# 类数基线：接了客户端源集之后每家 +4（见 STATE.md）
$ExpectClasses = @{ fabric = 19; forge = 19; neoforge = 21 }
$ExpectActions = @{ fabric = 9; forge = 7; neoforge = 7 }   # action 包下的 .class（含内部类）

function Get-Version {
    $p = Join-Path $Root 'lab\fabric\gradle.properties'
    $line = Get-Content $p | Where-Object { $_ -match '^version=' } | Select-Object -First 1
    if (-not $line) { throw "lab\fabric\gradle.properties 里没有 version=" }
    return ($line -split '=', 2)[1].Trim()
}

function Get-JarStats([string]$JarPath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entries = @($zip.Entries)
        return [pscustomobject]@{
            classes = @($entries | Where-Object { $_.FullName -like '*.class' }).Count
            actions = @($entries | Where-Object { $_.FullName -like 'com/mcdev/probe/action/*.class' }).Count
            entries = $entries.Count
            hasModJson = @($entries | Where-Object { $_.FullName -eq 'fabric.mod.json' -or $_.FullName -eq 'META-INF/mods.toml' -or $_.FullName -eq 'META-INF/neoforge.mods.toml' }).Count -gt 0
        }
    } finally { $zip.Dispose() }
}

$version = Get-Version
$suiteName = "mcdev-probe-suite-$version"
$rows = New-Object System.Collections.ArrayList

# ---------- 1) 构建 + 2) 核对 ----------
foreach ($family in 'fabric', 'forge', 'neoforge') {
    $dir = Join-Path $Root "lab\$family"
    if (-not (Test-Path $dir)) { throw "缺少目录：$dir" }
    Push-Location $dir
    try {
        if (-not $SkipBuild) {
            Write-Host ("[{0}] 构建 {1} …" -f (Get-Date -Format 'HH:mm:ss'), $family)
            $out = & .\gradlew.bat --no-daemon --console=plain --rerun-tasks build 2>&1
            if ($LASTEXITCODE -ne 0) {
                $out | Select-Object -Last 30 | ForEach-Object { Write-Host $_ }
                throw "$family 构建失败（exit $LASTEXITCODE）"
            }
            $warnings = @($out | Select-String -Pattern '警告:|warning:' | Where-Object { $_.Line -notmatch '^\s*$' }).Count
            $errors = @($out | Select-String -Pattern '错误:|error:' | Where-Object { $_.Line -notmatch '^\s*$' }).Count
            if ($errors -gt 0) { throw "$family 有 $errors 条 javac 错误" }
            if ($warnings -gt 0) {
                $out | Select-String -Pattern '警告:|warning:' | ForEach-Object { Write-Host "  $($_.Line)" }
                throw "$family 有 $warnings 条 javac 警告（零警告是验收项）"
            }
        }
        $jar = Get-ChildItem (Join-Path $dir 'build\libs') -Filter '*.jar' |
            Where-Object { $_.Name -notlike '*sources*' } | Select-Object -First 1
        if (-not $jar) { throw "$family 的 build\libs 里没有 jar" }
        $stats = Get-JarStats $jar.FullName
        if ($stats.classes -ne $ExpectClasses[$family]) {
            throw ("{0} 的类数 {1} ≠ 基线 {2}（增量构建漏类？用 --rerun-tasks）" -f $family, $stats.classes, $ExpectClasses[$family])
        }
        if (-not $stats.hasModJson) { throw "$family 的 jar 里没有 mod 元数据文件" }
        $null = $rows.Add([pscustomobject]@{
                family = $family; file = $jar.Name; bytes = $jar.Length
                sha256 = (Get-FileHash $jar.FullName -Algorithm SHA256).Hash.ToLower()
                classes = $stats.classes; actions = $stats.actions; entries = $stats.entries
                source = $jar.FullName; built = $jar.LastWriteTime.ToString('s')
            })
    } finally { Pop-Location }
}

# ---------- 3) 组装套件目录 ----------
$target = Join-Path $OutDir $suiteName
$zipPath = Join-Path $OutDir "$suiteName.zip"
foreach ($p in @($target, $zipPath)) {
    if (Test-Path $p) {
        if (-not $Force) { throw "目标已存在：$p（确认覆盖请加 -Force）" }
        Remove-Item $p -Recurse -Force
    }
}
New-Item -ItemType Directory -Force -Path $target | Out-Null
$null = New-Item -ItemType Directory -Force -Path (Join-Path $target 'mods') | Out-Null
$null = New-Item -ItemType Directory -Force -Path (Join-Path $target 'scripts') | Out-Null
$null = New-Item -ItemType Directory -Force -Path (Join-Path $target 'dsh-plugin') | Out-Null

foreach ($r in $rows) {
    Copy-Item $r.source -Destination (Join-Path $target 'mods' ("mcdev-probe-{0}-{1}.jar" -f $version, $r.family)) -Force
}

# 插件 + 脚本 + 场景（发布物里是全套，但只有 install.ps1 是入口）
Copy-Item (Join-Path $Root 'tools\dsh-plugin\mcdev-devrun') -Destination (Join-Path $target 'dsh-plugin\mcdev-devrun') -Recurse -Force
foreach ($s in 'install.ps1', 'install-dsh-plugin.ps1', 'devrun.ps1', 'package.ps1') {
    Copy-Item (Join-Path $Root "scripts\$s") -Destination (Join-Path $target "scripts\$s") -Force
}
Copy-Item (Join-Path $Root 'scenarios') -Destination (Join-Path $target 'scenarios') -Recurse -Force
Copy-Item (Join-Path $Root 'LICENSE') -Destination (Join-Path $target 'LICENSE') -Force

# ---------- README（套件级）----------
$readme = @"
# MCDev Probe 套件 $version

**一个套件，一个版本号，一个附件。** Minecraft 26.3 / Java 25。
本地 HTTP 探针：给自动化测试做「观察 + 操作」，服务端和客户端各一份；零 mixin，纯加载器入口，共享实现 + 三家薄适配。

## 30 秒上手

``````powershell
# 1) 解压后，把对应加载器的那份放进实例的 mods/（脚本会顺便校验 sha256）
pwsh -NoProfile -File .\scripts\install.ps1 -Loader fabric -Target "<MC_ROOT>\Server"

# 2) 可选：顺便把 DSH 插件装上（装完重启 DSH，且要在**新会话**里才看得到工具）
pwsh -NoProfile -File .\scripts\install.ps1 -Loader fabric -Target "<MC_ROOT>\Server" -DshPlugin
``````

三个 jar 都在 `mods/` 里，**只装你那个加载器的那一份**：

| 加载器 | jar | 放到 |
|---|---|---|
| Fabric | `mods/mcdev-probe-$version-fabric.jar` | 服务端 / 客户端 `mods/` |
| Forge | `mods/mcdev-probe-$version-forge.jar` | 同上 |
| NeoForge | `mods/mcdev-probe-$version-neoforge.jar` | 同上 |

## 起起来

| 端 | 默认端口 | 覆盖方式 |
|---|---|---|
| 服务端探针 | 39001 | `-Dmcdev.probe.port=39011` |
| 客户端探针 | 39002 | `-Dmcdev.probe.client.port=39002` |
| 鉴权 | 头 `X-Mcdev-Token: mcdev` | `-Dmcdev.probe.token=...` |
| 客户端调服务端探针（`mode:"server-teleport"`） | `http://127.0.0.1:39001` | `-Dmcdev.probe.server.url=http://127.0.0.1:39011` |

## 端点

- 观察：`GET /status`、`/log/tail`、`/world/block`、`/player/state`、`/entity/list`、`/item/mainhand`、`/block/sign`、`/block/command-block`
- 动作（服务端 9 个）：`POST /action/{teleport,gamemode,ability,mine,place,use,attack,pick,inventory}`
- 动作（客户端 12 个）：`POST /action/{move,mine,place,use,attack,pick,inventory,gamemode,ability,spectate,respawn,sleep}`
- 脚本：`POST /command`、`/chat/send`、`/block/sign/edit`、`/connect`、`/quit`、`/screenshot`

信封统一：成功 `{"ok":true,"data":{...}}`；失败 `{"ok":false,"error":{"code":"...","message":"...","detail":{...}}}`
（`code` ↔ HTTP：400 BAD_REQUEST / 404 PLAYER_NOT_FOUND|TARGET_NOT_FOUND / 409 FROZEN / 501 TRAIT_UNAVAILABLE / 504 TIMEOUT）。
**错误码也是契约** —— 响亮失败，不静默成功。

## 套件里还有什么

| 路径 | 说明 |
|---|---|
| `dsh-plugin/mcdev-devrun/` | DSH 插件：`mcdev_devrun`（起服→起客户端→连服→跑场景→取证→收尾）与 `mcdev_scenarios` |
| `scripts/devrun.ps1` | 端到端编排脚本本体（插件只是它的薄包装） |
| `scenarios/` | `smoke.json`（5 步）/ `actions.json`（17 步，三家全绿） |
| `scripts/package.ps1` | 重新打这个套件（开发用，需要三家 gradle 工程） |

## 校验

``````
sha256sum -c SHA256SUMS.txt
``````
"@
$readme | Set-Content -Path (Join-Path $target 'README.md') -Encoding utf8

# ---------- 校验和 + 清单 ----------
$sumLines = New-Object System.Collections.Generic.List[string]
foreach ($f in Get-ChildItem $target -Recurse -File | Sort-Object FullName) {
    $rel = $f.FullName.Substring($target.Length + 1).Replace('\', '/')
    $sumLines.Add(("{0}  {1}" -f (Get-FileHash $f.FullName -Algorithm SHA256).Hash.ToLower(), $rel))
}
$sumLines | Set-Content -Path (Join-Path $target 'SHA256SUMS.txt') -Encoding ascii

$manifest = [pscustomobject]@{
    name       = 'mcdev-probe-suite'
    kind       = 'suite'
    version    = $version
    minecraft  = '26.3'
    java       = 25
    builtAt    = (Get-Date).ToString('s')
    entrypoint = 'scripts/install.ps1'
    families   = @($rows | ForEach-Object {
            [pscustomobject]@{ family = $_.family; jar = ("mods/mcdev-probe-{0}-{1}.jar" -f $version, $_.family)
                bytes = $_.bytes; sha256 = $_.sha256; classes = $_.classes; actionClasses = $_.actions }
        })
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -Path (Join-Path $target 'manifest.json') -Encoding utf8

# ---------- 4) 单文件套件 ----------
Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
[System.IO.Compression.ZipFile]::CreateFromDirectory($target, $zipPath, [System.IO.Compression.CompressionLevel]::Optimal, $true)

# ---------- 5) 复验 ----------
$zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    $names = @($zip.Entries | ForEach-Object { $_.FullName.Replace('\', '/') })
} finally { $zip.Dispose() }

$required = @(
    "scripts/install.ps1", "scripts/install-dsh-plugin.ps1", "scripts/devrun.ps1",
    'README.md', 'manifest.json', 'SHA256SUMS.txt', 'LICENSE',
    'dsh-plugin/mcdev-devrun/dsh.mjs',
    'scenarios/smoke.json', 'scenarios/actions.json'
) + @($rows | ForEach-Object { "mods/mcdev-probe-$version-$($_.family).jar" })

$missing = @($required | Where-Object { $n = $_; -not ($names | Where-Object { $_ -like "*/$n" }) })
if ($missing.Count -gt 0) { throw "套件里缺文件：$($missing -join ', ')" }

# 「套件不含任何 MC 运行时」是用户 2026-10-20 定的边界，这里做成断言而不是靠自觉：
# 套件 = 三个探针 jar + 脚本 + DSH 插件 + 文档；加载器/MC 本体/资源一律由部署脚本去取。
$probeJarNames = @($rows | ForEach-Object { "mods/mcdev-probe-$version-$($_.family).jar" })
# 注意内层 Where-Object 的 $_ 会遮蔽外层的：先把名字存进 $n（这里踩过一次，
# 导致三个探针 jar 全被判成「非探针 jar」）。
$strayJars = @($names | Where-Object {
        $n = $_
        $n -like '*.jar' -and -not ($probeJarNames | Where-Object { $n -like "*/$_" })
    })
if ($strayJars.Count -gt 0) {
    throw "套件里出现了非探针 jar（不应携带任何加载器 / MC 运行时）：$($strayJars -join ', ')"
}
$runtimeDirs = @($names | Where-Object { $_ -match '(^|/)(libraries|assets|versions|runtime|world)/' })
if ($runtimeDirs.Count -gt 0) {
    throw "套件里出现了 MC 运行时目录：$((@($runtimeDirs) | Select-Object -First 5) -join ', ')"
}
$zipObj = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
try { $uncompressed = [long](($zipObj.Entries | Measure-Object -Property Length -Sum).Sum) } finally { $zipObj.Dispose() }
if ($uncompressed -gt 5MB) {
    throw ("套件解压后 {0:N0} B，超过 5 MB 护栏 —— 检查是否误打包了运行时" -f $uncompressed)
}
Write-Host ("  纯度断言 ✓ 无非探针 jar、无运行时目录、解压 {0:N0} B < 5 MB" -f $uncompressed)

# 逐文件校验和复验（套件目录内）
$bad = 0
foreach ($line in $sumLines) {
    $hash, $rel = $line -split '\s+', 2
    $full = Join-Path $target $rel.Replace('/', '\')
    if ((Get-FileHash $full -Algorithm SHA256).Hash.ToLower() -ne $hash) { Write-Host "  ✗ $rel"; $bad++ }
}
if ($bad -gt 0) { throw "$bad 个文件校验和不符" }

$zipItem = Get-Item $zipPath
$zipHash = (Get-FileHash $zipPath -Algorithm SHA256).Hash.ToLower()

Write-Host ''
$rows | Select-Object family, file, bytes, classes, actions, @{ n = 'sha256'; e = { $_.sha256.Substring(0, 16) + '…' } } |
    Format-Table -AutoSize | Out-String -Width 200 | Write-Host
Write-Host "套件目录 : $target"
Write-Host "单文件   : $zipPath"
Write-Host ("           {0:N0} B / {1} 个条目 / sha256 {2}" -f $zipItem.Length, $names.Count, $zipHash)
Write-Host ("文件数   : {0}（校验和全部通过）" -f $sumLines.Count)
