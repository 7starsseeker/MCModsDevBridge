#Requires -Version 7.0
<#
.SYNOPSIS
按一份「用例文件」串行跑多个 devrun 场景，每个用例自带服务端/客户端要装的 mod，跑完精确卸掉。

.DESCRIPTION
一个用例 = 一个实例 + 一个场景 + 两侧要装的 mod。脚本为每个用例：

  1. 把 `serverMods` 装进实例的服务端目录（`scripts\install-mod.ps1 -Side server`）
  2. 把 `clientMods` 装进实例的客户端游戏目录（`-Side client`）
  3. 起一个 devrun 子进程跑该场景，取证落到 `<EvidenceDir>\<用例名>\`
  4. 把两边装过的 mod 精确卸掉（只卸本脚本装的，见 install-mod 的记录）
  5. 读 `summary.md` 的 `PASS=n FAIL=m total=t` 行，按用例声明的期望判「绿 / 红 / 符合预期」

为什么要它对：`devrun -Mods` 只管「跑一次顺便拷进去」，换 case 时残留的 mod 会污染下一轮。
用例文件把「哪个实例 + 哪个场景 + 两侧装什么 + 期望什么」写成数据，才能把
「四类 mod × 三种加载器」这类组合当矩阵跑，而不是手敲一堆命令。

用例文件格式（JSON）：

```json
{
  "cases": [
    {
      "name": "fixture-both-fabric",
      "instance": "fam-fabric",
      "scenario": "scenarios/fixtures/fixture-both.json",
      "serverMods": ["lab/fixtures/fixture/build/libs/mcdev_fixture-1.0.0.jar"],
      "clientMods": ["lab/fixtures/fixture/build/libs/mcdev_fixture-1.0.0.jar"]
    },
    {
      "name": "expected-missing-half",
      "instance": "fam-fabric",
      "scenario": "scenarios/fixtures/fixture-client-missing.json",
      "serverMods": ["lab/fixtures/fixture/build/libs/mcdev_fixture-1.0.0.jar"],
      "expectFail": true
    }
  ]
}
```

字段：

  · `name`       必填，用例名，同时是取证子目录名
  · `instance`   必填，`instances\<名字>.json` 的名字（或一个 json 路径）
  · `scenario`   必填，场景文件路径（相对仓库根）
  · `serverMods` 可选，装进服务端 `mods\` 的 jar（路径相对仓库根）
  · `clientMods` 可选，装进客户端游戏目录 `mods\` 的 jar
  · `expectFail` 可选，默认 `false`；`true` 表示「这个用例本来就该红」，红了才算符合预期
  · `note`       可选，写进汇总表，说明这条在验什么

.PARAMETER Cases
用例文件路径（相对仓库根或绝对路径）。

.PARAMETER EvidenceDir
取证根目录，每个用例一个子目录。缺省 `tmp\matrix-<时间戳>`。

.PARAMETER StopOnFail
某条用例不符合预期就停下，不再跑后面的。

.PARAMETER JavaExe
透传给 devrun（java.exe 路径）。缺省按 `JAVA_HOME` → PATH 解析。

.PARAMETER Proxy
透传给 devrun 的代理 URL（只有拉依赖时才需要）。

.PARAMETER NoProxy
透传给 devrun：强制不使用任何代理。

.EXAMPLE
pwsh -File scripts\devrun-matrix.ps1 -Cases scenarios\matrix-fixtures.json

.EXAMPLE
pwsh -File scripts\devrun-matrix.ps1 -Cases tmp\my-cases.json -EvidenceDir tmp\matrix-run1 -StopOnFail
#>
param(
    [Parameter(Mandatory = $true)][string]$Cases,
    [string]$EvidenceDir,
    [switch]$StopOnFail,
    [string]$JavaExe,
    [string]$Proxy,
    [switch]$NoProxy
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$Stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if (-not $EvidenceDir) { $EvidenceDir = Join-Path $Root "tmp\matrix-$Stamp" }
if (-not [System.IO.Path]::IsPathRooted($EvidenceDir)) { $EvidenceDir = Join-Path $Root $EvidenceDir }
if (-not (Test-Path -LiteralPath $EvidenceDir)) {
    New-Item -ItemType Directory -Path $EvidenceDir -Force | Out-Null
}
$EvidenceDir = (Resolve-Path -LiteralPath $EvidenceDir).Path

function Resolve-UnderRoot([string]$Path) {
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path $Root $Path)
}

function Say([string]$Message) {
    Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $Message)
}

# ---------------------------------------------------------------- 读用例文件

$casesPath = Resolve-UnderRoot $Cases
if (-not (Test-Path -LiteralPath $casesPath)) { throw "用例文件不存在：$casesPath" }
$casesObj = Get-Content -LiteralPath $casesPath -Raw | ConvertFrom-Json
if (-not $casesObj.PSObject.Properties['cases'] -or -not $casesObj.cases) {
    throw "用例文件里没有 cases 数组：$casesPath"
}
$caseList = @($casesObj.cases)
if ($caseList.Count -eq 0) { throw "用例文件里 cases 是空的：$casesPath" }

$seen = @{}
foreach ($c in $caseList) {
    foreach ($field in 'name', 'instance', 'scenario') {
        if (-not $c.PSObject.Properties[$field] -or -not $c.$field) {
            throw "用例缺少字段 '$field'：$($c | ConvertTo-Json -Compress)"
        }
    }
    if ($seen.ContainsKey($c.name)) { throw "用例名重复：$($c.name)" }
    $seen[$c.name] = $true
    $scenarioPath = Resolve-UnderRoot $c.scenario
    if (-not (Test-Path -LiteralPath $scenarioPath)) { throw "用例 '$($c.name)' 的场景文件不存在：$scenarioPath" }
}

Say "用例文件：$casesPath（$($caseList.Count) 条）"
Say "取证根目录：$EvidenceDir"

# ---------------------------------------------------------------- 实例目录（装/卸 mod 要用）

function Get-InstancePaths([string]$Spec) {
    $path = if ($Spec -match '[\\/]' -or $Spec -like '*.json') { Resolve-UnderRoot $Spec } else { Join-Path $Root "instances\$Spec.json" }
    if (-not (Test-Path -LiteralPath $path)) { throw "找不到实例登记：$path（先用 register-instance.ps1 登记，或用 -Family 的内置默认）" }
    $cfg = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    $serverDir = $null
    $clientGameDir = $null
    if ($cfg.PSObject.Properties['dir'] -and $cfg.dir) { $serverDir = [string]$cfg.dir }
    if ($cfg.PSObject.Properties['client'] -and $cfg.client -and $cfg.client.PSObject.Properties['gameDir'] -and $cfg.client.gameDir) {
        $clientGameDir = [string]$cfg.client.gameDir
    }
    return [pscustomobject]@{ path = $path; cfg = $cfg; serverDir = $serverDir; clientGameDir = $clientGameDir }
}

# ---------------------------------------------------------------- 逐条跑

$rows = New-Object System.Collections.ArrayList
$allOk = $true

foreach ($c in $caseList) {
    $name = [string]$c.name
    $note = if ($c.PSObject.Properties['note'] -and $c.note) { [string]$c.note } else { '' }
    $expectFail = [bool]($c.PSObject.Properties['expectFail'] -and $c.expectFail)
    $caseDir = Join-Path $EvidenceDir $name
    if (Test-Path -LiteralPath $caseDir) {
        # 同名用例重跑：清掉上一轮的取证，免得把旧 summary 当新结果
        Remove-Item -LiteralPath $caseDir -Recurse -Force
    }
    New-Item -ItemType Directory -Path $caseDir -Force | Out-Null

    Say "===== 用例 $name（实例 $($c.instance)，$($c.scenario)）====="

    $installedServer = $false
    $installedClient = $false
    $errorText = $null
    $summaryLine = 'n/a'
    $code = $null

    try {
        # 实例/用例的配置错误也放进 try：一条配错的用例不该让整个矩阵连结果都不记录就退出
        $paths = Get-InstancePaths ([string]$c.instance)
        $serverMods = @()
        $clientMods = @()
        if ($c.PSObject.Properties['serverMods'] -and $c.serverMods) { $serverMods = @($c.serverMods | ForEach-Object { Resolve-UnderRoot $_ }) }
        if ($c.PSObject.Properties['clientMods'] -and $c.clientMods) { $clientMods = @($c.clientMods | ForEach-Object { Resolve-UnderRoot $_ }) }
        foreach ($m in @($serverMods) + @($clientMods)) {
            if (-not (Test-Path -LiteralPath $m)) { throw "要装的 jar 不存在：$m（先构建它）" }
        }

        if ($serverMods.Count -gt 0) {
            if (-not $paths.serverDir) { throw "用例 '$name' 有 serverMods，但实例没有 dir（服务端目录）" }
            & pwsh -NoProfile -File (Join-Path $PSScriptRoot 'install-mod.ps1') -Side server -Target $paths.serverDir -Jar ($serverMods -join ',') *> (Join-Path $caseDir 'install-server.log')
            if ($LASTEXITCODE -ne 0) { throw "装服务端 mod 失败（见 $caseDir\install-server.log）" }
            $installedServer = $true
        }
        if ($clientMods.Count -gt 0) {
            if (-not $paths.clientGameDir) { throw "用例 '$name' 有 clientMods，但实例没有 client.gameDir" }
            & pwsh -NoProfile -File (Join-Path $PSScriptRoot 'install-mod.ps1') -Side client -Target $paths.clientGameDir -Jar ($clientMods -join ',') *> (Join-Path $caseDir 'install-client.log')
            if ($LASTEXITCODE -ne 0) { throw "装客户端 mod 失败（见 $caseDir\install-client.log）" }
            $installedClient = $true
        }

        $childArgs = @('-NoProfile', '-File', (Join-Path $PSScriptRoot 'devrun.ps1'),
            '-Instance', [string]$c.instance, '-Scenario', (Resolve-UnderRoot ([string]$c.scenario)),
            '-EvidenceDir', $caseDir)
        if ($JavaExe) { $childArgs += @('-JavaExe', $JavaExe) }
        if ($Proxy) { $childArgs += @('-Proxy', $Proxy) }
        if ($NoProxy) { $childArgs += '-NoProxy' }
        & pwsh @childArgs *> (Join-Path $caseDir 'matrix.log')
        $code = $LASTEXITCODE
    } catch {
        $errorText = $_.Exception.Message
        Say "用例 $name 跑挂了：$errorText"
    } finally {
        # 卸载必须做：下一轮的 /mods 断言依赖「这一轮装的东西都不在了」
        if ($installedServer) {
            & pwsh -NoProfile -File (Join-Path $PSScriptRoot 'install-mod.ps1') -Target $paths.serverDir -Remove *> (Join-Path $caseDir 'uninstall-server.log')
            if ($LASTEXITCODE -ne 0) { Say "警告：卸载服务端 mod 没成功（见 $caseDir\uninstall-server.log）" }
        }
        if ($installedClient) {
            & pwsh -NoProfile -File (Join-Path $PSScriptRoot 'install-mod.ps1') -Target $paths.clientGameDir -Remove *> (Join-Path $caseDir 'uninstall-client.log')
            if ($LASTEXITCODE -ne 0) { Say "警告：卸载客户端 mod 没成功（见 $caseDir\uninstall-client.log）" }
        }
    }

    if (-not $errorText) {
        $summaryPath = Join-Path $caseDir 'summary.md'
        if (Test-Path -LiteralPath $summaryPath) {
            $hit = Select-String -Path $summaryPath -Pattern 'PASS=\d+ FAIL=\d+ total=\d+' -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($hit) { $summaryLine = $hit.Matches.Value }
        }
    }

    $failed = ($code -ne 0)
    $asExpected = if ($expectFail) { $failed } else { (-not $failed) }
    if ($errorText) { $asExpected = $false }
    if (-not $asExpected) { $allOk = $false }

    $verdict = if ($errorText) { '跑挂' }
    elseif ($asExpected) { if ($expectFail) { '符合预期（该红）' } else { '绿' } }
    else { if ($expectFail) { '意外变绿（期望失败）' } else { '红' } }

    Say ("用例 {0}：{1}（exit={2}，{3}）" -f $name, $verdict, $(if ($null -eq $code) { "n/a" } else { $code }), $summaryLine)

    $null = $rows.Add([pscustomobject]@{
            case     = $name
            instance = [string]$c.instance
            scenario = [string]$c.scenario
            exit     = if ($null -eq $code) { 'n/a' } else { $code }
            summary  = $summaryLine
            verdict  = $verdict
            expectFail = $expectFail
            note     = $note
            error    = if ($errorText) { $errorText } else { '' }
        })

    if (-not $asExpected -and $StopOnFail) {
        Say '按 -StopOnFail 停下。'
        break
    }
}

# ---------------------------------------------------------------- 汇总

$okCount = @($rows | Where-Object { $_.verdict -eq '绿' -or $_.verdict -eq '符合预期（该红）' }).Count
$badCount = $rows.Count - $okCount

Write-Host ''
$rows | Format-Table -AutoSize -Property case, instance, exit, summary, verdict | Out-String -Width 220 | Write-Host

$lines = New-Object System.Collections.ArrayList
$null = $lines.Add('# devrun 矩阵汇总')
$null = $lines.Add('')
$null = $lines.Add("- 用例文件：$casesPath")
$null = $lines.Add("- 时间：$((Get-Date).ToString('o'))")
$null = $lines.Add("- 结论：**$okCount/$($rows.Count) 条符合预期**")
$null = $lines.Add('')
$null = $lines.Add('| 用例 | 实例 | 场景 | exit | summary | 判定 | 说明 |')
$null = $lines.Add('|---|---|---|---|---|---|---|')
foreach ($r in $rows) {
    $note = if ($r.note) { $r.note } else { '' }
    $null = $lines.Add("| ``$($r.case)`` | $($r.instance) | $($r.scenario) | $($r.exit) | $($r.summary) | $($r.verdict) | $note |")
}
$null = $lines.Add('')
$null = $lines.Add('每个用例的完整取证在 `<用例名>\` 子目录里（`summary.md` / `results.json` / `server.out` / `client.out`）；')
$null = $lines.Add('`install-*.log` / `uninstall-*.log` 记的是这一轮装了什么、卸了什么。')
$summaryFile = Join-Path $EvidenceDir 'matrix-summary.md'
[System.IO.File]::WriteAllText($summaryFile, (($lines -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))

Write-Host "汇总：$summaryFile"
if (-not $allOk -or $badCount -gt 0) {
    Write-Host "矩阵结论：$badCount 条不符合预期"
    exit 1
}
Write-Host "矩阵结论：$okCount/$($rows.Count) 条全部符合预期"
exit 0
