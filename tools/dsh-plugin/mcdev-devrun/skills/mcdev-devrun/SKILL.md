# 端到端编排（mcdev-devrun）

> 一条命令跑完「准备 → 起服 → 起客户端 → 连服 → 操作场景 → 取证 → 收尾」。三种入口共用同一套实现：
> DSH 工具 `mcdev_devrun`、`scripts/devrun.ps1`、`scripts/devrun-matrix.ps1`（多用例矩阵）。
> **逻辑只在 ps1 里**，插件是薄包装——所以排障时读脚本永远比读插件有用。

## 1. 前置：先有实例登记，再谈跑

**发布决策（用户 2026-10-20）：套件不携带任何 MC 客户端 / 服务端 / 加载器。** 所以第一步永远是
「把你自己机器上的实例登记进来」：

| 场景 | 命令 |
|---|---|
| 不知道自己要哪种组合 | `scripts/setup-wizard.ps1`（问加载器 / mod 在哪端 / 现成还是新建 / 网络怎么走，写 `instances\`） |
| 已有现成实例 | `scripts/register-instance.ps1 -Name fabric -Dir <含 mods 的目录> -Side both -Client <客户端游戏目录>` |
| 手上还没有服务端 | `scripts/provision-instance.ps1 -CopyFrom <已有目录>` 或 `-Download`（可 `-Proxy` / `-Offline`，fabric 可 `-Mirror`） |
| 只是自检登记对不对 | `devrun.ps1 -Instance fabric -PreflightOnly`（只打印计划，不起服） |

- `-Family <fabric|forge|neoforge>` 只是「按加载器找同名登记」的简写；`-Instance <名字|json路径>`
  是显式指定。两者都不给 = `-Family fabric`。
- 三种加载器都不沾的环境本套件帮不上；但如果某个启动器**最终起的是被支持的加载器**，可以用登记里的
  `start.exe` 接上，探针端口通过 `JAVA_TOOL_OPTIONS` 注入，不需要知道它的命令行形状。

## 2. 常用参数

| 参数 | 说明 |
|---|---|
| `-Family` | `fabric` / `forge` / `neoforge` / `all`（矩阵：三家串行） |
| `-Instance` | 实例名或 json 路径 |
| `-Scenario` | 场景文件，默认 `scenarios\smoke.json` |
| `-Mods` | 顺便拷进测试服 `mods\` 的 jar（**不记账、不卸**；要可卸用 `install-mod.ps1` 或矩阵） |
| `-ProbeJar` | 手上只有 dist 里的探针 jar、没有 lab 工程时显式指定 |
| `-JavaExe` / `-Proxy` / `-NoProxy` | 没有 `JAVA_HOME` 或要指定代理时用；默认解析链是 显式 → 登记 → `HTTPS_PROXY` → 系统代理 |
| `-EvidenceDir` | 取证目录，默认 `tmp\devrun-<family>-<yyyyMMdd-HHmmss>` |
| `-KeepRunning` | 跑完不收尾，留服和客户端给人接手 |
| `-PreflightOnly` | 只解析登记 + 打印计划 |
| `-ServerTimeoutSec` / `-ClientTimeoutSec` / `-ConnectTimeoutSec` | 三段超时，默认 240 / 300 / 90 秒 |

**矩阵模式的硬约束**：`-Family all` **不接受** `-EvidenceDir` / `-KeepRunning`——每家要独占端口、
各自 finally 收尾，子进程跑挂了也不能污染下一家。客户端探针端口三家共用 39002，所以**同一时刻
只能有一个客户端在跑**，矩阵只能串行。

## 3. 多用例矩阵（devrun-matrix.ps1）

「一个用例 = 一个实例 + 一个场景 + 两侧要装的 mod」写成 JSON 就能批量跑：

```json
{
  "cases": [
    { "name": "fixture-both-fabric", "instance": "fam-fabric",
      "scenario": "scenarios/fixtures/fixture-both.json",
      "serverMods": ["lab/fixtures/fixture/build/libs/mcdev_fixture-1.0.0.jar"],
      "clientMods": ["lab/fixtures/fixture/build/libs/mcdev_fixture-1.0.0.jar"] },
    { "name": "expected-missing-half", "instance": "fam-fabric",
      "scenario": "scenarios/fixtures/fixture-client-missing.json",
      "serverMods": ["lab/fixtures/fixture/build/libs/mcdev_fixture-1.0.0.jar"],
      "expectFail": true }
  ]
}
```

- `serverMods` / `clientMods` 会经 `install-mod.ps1` 装、跑完**精确卸掉**（只卸本脚本装的，按 sha256 核对）。
  这是它和 `devrun -Mods` 的区别：后者换 case 时会残留污染。
- `expectFail: true` = 「这个用例本来就该红」，红了才算符合预期 —— 用来表达**故意缺一半**的场景。
- `-StopOnFail` 让第一条不符合预期的用例直接停下。
- 每个用例的取证落在 `<EvidenceDir>\<用例名>\`，汇总表读各目录 `summary.md` 的 `PASS=n FAIL=m total=t`。

## 4. 取证目录长什么样

```
tmp\devrun-<family>-<时间戳>\
  results.json      每步的请求/响应/判定（机器可读，排障主入口）
  summary.md        人读的结论 + PASS/FAIL 汇总 + 每步 note
  server.out        服务端 stdout（含启动、探针注册日志）
  server.err        服务端 stderr（栈、拒绝加载的原因）
  client.out        客户端 stdout
  run-client.ps1    本次生成的内层启动脚本（复用/复现现场用）
```

- 判「是不是环境问题」先看 `server.err` 与 `client.out` 尾部，再看 `results.json` 里是哪一步开始红的。
- 截图不在判定链里（见 §6），要留就自己另存。

## 5. 场景 JSON

语法（`expect` 四种断言、`retry`、`waitMs`、`{{player}}`、`note`）见
`references/scenario-dsl.md`；**要直接抄模板就翻 `references/scenario-cookbook.md`**
（造景→动作→回读、跨维度、跨端传播、负例、背包、客户端动作、单人世界、七条常见错法）；
现成例子看 `scenarios\smoke.json`（5 步，含一条**故意失败**的错误码契约）
与 `scenarios\capabilities.json`（14 步，跨维度 forceload/读写/清理 + 聊天与动作栏显示）。

## 6. 判定边界：什么进判定，什么只能人眼

- **判定只来自机器可读证据**：探针响应、服务端日志尾部、方块/背包/实体回读。
- **截图是端点但不是断言**：`GET /screenshot` 能拿图，但「什么时机该截、图里算什么对着」跟具体 mod 强相关，
  硬塞进编排只会得到「拍了很多图但没人看」。要人眼证据就 `-KeepRunning` 后自己截。
- GUI 视觉、光影、动画、手感这类，本套件的答案是**交给人 + computer use 接管**，不是硬编码进场景。

## 7. 红了怎么定因

先看 `references/troubleshooting.md` 的对照表（连不上 / 白名单 / 端口占用 / stdin EOF / GBK 日志锁 /
代理卡住 / 探针 jar 与加载器不匹配 / Java 版本）。**遇到「看着像没生效」先怀疑静默失败**——
26.3 那一类坑在 `mc26-multiloader-traps` 技能里。
