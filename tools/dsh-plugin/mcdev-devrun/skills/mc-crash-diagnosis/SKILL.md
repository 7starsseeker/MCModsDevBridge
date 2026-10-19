# 「起不来」三分类：没加载 / 初始化炸 / 进程没起（mc-crash-diagnosis）

给「服或客户端起不来 / 看起来起来了但没反应」用的。命中词：崩溃、起不来、闪退、超时、crash、
latest.log、crash-report、异常、栈、NoClassDefFoundError、mixin 报错、客户端连不上探针。

## 0. 先分类，再看证据

三种情况长得像、修法完全不同：

| 类 | 现象 | 判别入口 |
|---|---|---|
A 进程根本没起 | `/status` 不通、`gradlew` 退出码非 0、取证目录里 `*.err` 有栈 | 进程 + 文件 |
B 进程起了、mod 没加载 | `/status` 通、`/mods` 里没有你的 id | `/mods` |
C 加载了但初始化/注册炸 | `/mods` 里有、`/status` 通，但功能没生效、日志有异常 | `/log/tail` + 文件 |

**顺序固定：`/status` → `/mods` → `/log/tail` → 落到文件。** 每一层只回答一个问题，别跳层猜。

## 1. `/status` 通不通：先分清「没起来」和「探针没连上」

`/status` 不通有两种完全不同的原因，最容易被混为一谈：

- **端口不是你以为的那个**。服务端探针默认 39001、客户端 39002；`devrun.ps1` 里记过一次真实事故：
  启动参数落进 Minecraft 参数区 ⇒ 探针退到默认 39001，而脚本按 39011 等 ⇒ 240 秒超时，
  看起来像「服务端起不来」，其实它 8 秒就起来了。
- 真没起来（进程池里没有、`.err` 里有栈）。

先看**端口**再怀疑崩溃。超时类失败的第一动作永远是「端口/pid 对不对」，不是翻日志。

## 2. `/mods` 决定 B 还是 C

`/mods` 给的是**加载器自己的清单**（`ids` 数组），不是探针猜的。

- 你的 id 不在 → **B：没加载**。常见原因：jar 没拷进 `mods/`、jar 名字/结构不对、加载器版本不符
  （26.x 的包结构变了）、`fabric.mod.json`/`mods.toml` 的元数据写错、依赖缺失被加载器跳过。
  **这一步看起来像「装了一半」时，用 `mcdev-fixture-design` 的四层判据，别用日志猜。**
- id 在里面但功能不生效 → **C**：加载 ≠ 生效。`/status` 里的 `actions`/`capabilities` 是探针自报的，
  能看出探针自己的注册有没有跑完。

## 3. `/log/tail` 能证明什么、不能证明什么

服务端/客户端的 `/log/tail?n=` 是**探针装上的 log4j appender 的环形缓冲**（上限 1000 行，从探针初始化那一刻开始）。

- ✅ 能抓：加载器/模组初始化阶段的异常、`WARN`/`ERROR`、你自己打的日志。
- ❌ 抓不到：**探针自己装之前**的日志（恰恰是「加载阶段就炸了」的那段）、非 log4j 通道的输出
  （`System.out`/`System.err` 直写、JVM 自身的 `hs_err`）、超过 1000 行被挤掉的历史。
- 所以：**`/log/tail` 干净 ≠ 没异常**。判「没炸」要落到文件。

## 4. 落到文件：四个位置

| 文件 | 在哪 | 什么时候看 |
|---|---|---|
| 取证目录 `server.out` / `server.err` / `client.out` | `devrun` 的 `-EvidenceDir`（默认在 `evidence\<时间戳>\`） | 脚本捕获的进程输出，含 PowerShell 的启动失败信息 |
| 实例的 `logs\latest.log` | 实例目录（`instances\<name>.json` 指的那个） | 游戏自己的完整日志，**加载阶段全在这** |
| 实例的 `logs\debug.log` 等轮转日志 | 同上 | `latest.log` 被下一轮覆盖后找历史 |
| `crash-reports\crash-*.txt` | 实例目录 | 硬崩（JVM 级/主线程异常）时的结构化报告 |

`hs_err_pid*.log`（JVM 自身崩溃，native 层）出现在**工作目录**，不在 `logs\` 下。

> `[推断]` 上面四条路径名是原版/Forge 系的惯例，本仓的取证只覆盖到 `server.out`/`client.out` 与
> `/log/tail`；`crash-reports\` 与 `hs_err` 未在 26.3 真机上核对过，第一次用请先 `ls` 确认再引用。

## 5. 三条常见的「看起来是崩溃」

- **客户端探针连不上**：客户端还在 `runClient` 的 gradle 启动期（要等窗口出来 + 探针端口才监听），
  不是崩。用「等端口」而不是「等固定秒数」。
- **矩阵里第二个加载器起不来**：39002 没释放（上一个客户端 `/quit` 后 gradle 还要几秒）。串行跑，
  或等端口再起。
- **`NoClassDefFoundError` 出现在专用服务器**：那是**客户端类泄漏**，不是崩——见 `mc26-mod-skeleton`
  的 split source sets 一节。

## 6. 把诊断变成证据

失败时取证目录里应该有：`results.json`（每步结果）、`summary.md`（人读结论）、`server.out`/`server.err`、
`client.out`。给用户的结论至少带三样：**失败步骤 id + 端口/进程事实 + 一行原始日志**。
「超时了」不是结论，「39011 等了 240 秒，探针实际在 39001（server.out 第 12 行有 `probe listening on 39001`）」
才是。

> `[推断]` 反模式清单里的「mixin 应用失败」具体文案在各加载器不同，本仓没有 26.3 的 mixin 崩溃样本，
> 遇到时先看 `crash-reports` 的 `Description:`/`-- MOD --` 段，不要凭记忆引用旧版本的报错格式。
