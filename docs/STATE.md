# 当前状态与续做入口

> **里程碑已封存：[milestone-2026-10-19-p0-p3.md](milestone-2026-10-19-p0-p3.md)**（可交接状态点）
> 最后更新：2026-10-19 22:57（**新环境部署链路打通**：`scripts/provision-instance.ps1` 覆盖三种网络形态
> ——直连 / 代理 / 完全离线（`-CheckOnly` 探 7 个端点；实测**只有 `maven.neoforged.net` 直连会 10s 超时，
> 走 `-Proxy http://127.0.0.1:<PROXY_PORT>` 变 7/7 全通**）；套件加了「不含任何 MC 运行时」的**打包断言**；
> 编排脚本支持实例登记 `instances/<name>.json`（启动器无关）与纯服务端场景 `scenarios/server-only.json`。
> 部署手册见 [deployment-topology.md](deployment-topology.md)）。下次继续请从「新会话从这里读」一节读起。
>
> **2026-10-19 23:45 增补（通用能力 + 覆盖审计）**：`docs/pairedportals-coverage-audit.md` 给出「第三方 mod
> 全自动化可测性」审计；据此补了四个**通用**能力 —— 客户端 `GET /chat/tail`（读客户端当前聊天/actionbar）、
> 服务端 `/world/block|break|mine`、`/items`、`/block/sign`、`/block/command-block` 的 `dim` 参数（未知维度
> 由静默回落改为 400）、场景 DSL 步骤级 `retry`、断言 `expect.contains`/`expect.text`。
> 自检场景 `scenarios/capabilities.json` 在 **Fabric / Forge / NeoForge 三家各实测 12/12 PASS**。G2（服务端
> `/player/state` 加 `dimension`）按需求方口径**不做**。

## 新会话从这里读（交接快照 2026-10-19 22:57）

**一句话**：探针（三家加载器、两端、9+12 个语义动作、聊天/告示牌/命令方块）＋ 端到端编排脚本 ＋
单套件发布 ＋ DSH 插件 + 新环境部署脚本，**全部可用且都有真机证据**；剩下的都不是「能不能跑」而是「要不要这么定」。

### 1. 现在的物理事实

| 东西 | 位置 | 状态 |
|---|---|---|
| 探针源码 | `lab/{fabric,forge,neoforge}/` + `lab/shared/src/{main,client}/java/com/mcdev/probe/` | 三家 `--rerun-tasks build` 零警告 |
| 构建产物 | `lab/*/build/libs/mcdev_probe-1.0.0.jar` | fabric 115,935 / forge 112,184 / neoforge 115,739 B（19/19/21 类） |
| 单套件 | `dist/mcdev-probe-suite-1.0.0.zip` | 377,524 B / 16 条目 / 14 文件，校验和全过，纯度断言通过 |
| 三台测试服 | `<MC_ROOT>\Server{,1,2}`（25566/25567/25568，探针 39011/39012/39013） | jar 已部署；**当前没有进程在跑** |
| 生产服 | `<MC_ROOT>\Server-26.3`（25565） | 未碰、未跑 |
| 实例登记 | `instances/{fabric,forge,neoforge,wizsrv}.json` | `devrun -Instance <name>` 读它 |
| DSH 插件 | `<DSH_HOME>\plugins\mcdev-devrun\dsh.mjs` | **已装且已注册**（`applied.json` 里 pid 16452、tools=`mcdev_devrun`,`mcdev_scenarios`）；当前会话的工具表是会话创建时固化的，**新会话应该直接能看到这两个工具** |

### 2. 最常用的六条命令

```powershell
pwsh -NoProfile -File scripts\devrun.ps1 -Family all -Scenario scenarios\actions.json   # 三家矩阵（≈4 分钟）
pwsh -NoProfile -File scripts\devrun.ps1 -Instance wizsrv -Scenario scenarios\server-only.json  # 纯服务端（≈17 s）
pwsh -NoProfile -File scripts\package.ps1            # 重建 + 四道关卡 + 套件 zip
pwsh -NoProfile -File scripts\install.ps1 -Bundle . -Loader fabric -Target <服目录>    # 装探针 jar
pwsh -NoProfile -File scripts\provision-instance.ps1 -Name <x> -Loader fabric -Target <dir> -CheckOnly
pwsh -NoProfile -File scripts\setup-wizard.ps1       # 交互式：挑加载器/目录/端口，逐项说明
```

### 3. 下一段该做什么（按建议顺序）

1. **独立客户端实例形态**（唯一挡在「可分发给别人」前面的东西）：HMCL profile / 套件自带手写启动壳 /
   官方启动器 profile —— 三选一，我还没替用户定。定完把 `client.mode=external` 这条路真机跑一次。
2. **PairedPortals 门框贴牌用例**（用户要求「记下来之后要测」，已写在下方待测节）：
   门框内贴牌 + 写字，唯一没验证过的环节是「手放牌的落点」。审计与缺口见
   [pairedportals-coverage-audit.md](pairedportals-coverage-audit.md)（G1/G3/G4/G6 已补齐，可直接写
   `scenarios/pairedportals-core.json`）。
3. **P5 发布渠道**：GitHub release（单套件 zip）还是私有分发；版本策略（现在只有 `1.0.0`）。
4. **可选**：FakePlayer（无客户端 CI 才需要，当前已证明不需要）、58 包覆盖 24/58 要不要再扩。

### 4. 环境坑速查（每一条都真踩过，细节在 §环境 / 记录 §16.9）

- 起长命子进程用 `Start-Process -RedirectStandardOutput`；**接管道或 `>` 重定向都会「看起来卡死」**（java 继承句柄）。
- 客户端玩家名每次启动都变（`PlayerNNN`；Forge/NeoForge 是 `Dev`），必须从 `/status` 读后再 `op`。
- `-Dmcdev.probe.port` 必须在 `-jar`/`@args` 之前；不传就落到默认 39001。
- `/command` 的 `result` **不是**成功布尔；断言一律用 `/world/block`、`/player/state`、`/item/mainhand` 这类观察路由。
- 客户端动作受 reach 限制（4 格内），且 `mine` 前必须 `aimAt`；创造的「本地瞬时破块」不等于服务端已破。
- 直连只有 `maven.neoforged.net` 不通，其余直连可用；代理走 `-Proxy http://127.0.0.1:<PROXY_PORT>`。


## 一句话状态

**P0 地基 ✅、P1 共享库移植 ✅、P2 三套适配 ✅、P3 端差异（Fabric）✅、P4 步骤 0 去脚手架 ✅、P4 动作层（客户端 12 端点）✅、P4 服务端后端（**9 动作 + 10 个 inventory op**，两个套件 19/19 与 20/20）✅、P4 客户端入口（Fabric + Forge + NeoForge，**三家 `runClient` 真机 19/19**）✅、P4 编排脚本（`devrun.ps1` + 场景文件，**三家各 17/17**）✅。**
下一段：**独立客户端实例形态（HMCL / 自带启动壳 / 官方启动器，待用户拍板）** → PairedPortals 门框贴牌用例 → P5 发布渠道与版本策略。交接清单见上面的「新会话从这里读」。
聊天/特殊方块 UI 那批已落地（记录 §15）；`/chat/suggest` 故意不开（响应是异步推送，与「同步返回结果」的端点契约不合）。

- 服务端探针在 **Fabric / Forge / NeoForge 三家**都是 15 条路由全通（12×200 + 3×501 按设计），
  `/status` 返回 `side=server` + 正确 `loader`，三份骨架 `clean build` **零警告**。
- 客户端探针在 **Fabric** 上 17 条路由全通（**15×200 + 1×400 缺参 + 1×409 无目标**，无 501），
  `/connect` 能把客户端送进本地专用服（服务端 `players` 0→1）。
  P6 又加了 `/chat/tail`（第 18 条，读客户端当前聊天/actionbar），实测见 `scenarios/capabilities.json`。
- **`/ui/*` 五条在 26.3 上全部可用** —— `Screen` 从 `Minecraft` 搬到了 `net.minecraft.client.gui.Gui`
  （`mc.gui.screen()` / `setScreen(...)` / `overlay()`），实测能读控件树、能点按钮跳转界面。

| 阶段 | 实测记录 |
|---|---|
| P3 | [p3-client-record.md](p3-client-record.md) |
| P2 | [p2-adaptation-record.md](p2-adaptation-record.md) |
| P1 | [p1-port-record.md](p1-port-record.md) |
| P0–P3 全景 | [mc26.3-multi-loader-probe-feasibility.md](mc26.3-multi-loader-probe-feasibility.md) |
| 架构与不变量 | [architecture.md](architecture.md) |

## P3 交付物（已落盘）

| 路径 | 内容 |
|---|---|
| `lab/shared/src/client/java/com/mcdev/probe/ClientProbeServer.java` | **816 行**（原 788 → 砍到 584 → 补回 `/ui/*` 五条与槽位表后 816） |

**`/player/inventory` 的槽位表口径（2026-10-19 13:30 定，为将来接 access widener 留位）**：

- 返回 `menu` / `slotCount` / `slots[]`，每格含 `index` · `containerSlot` · `x` · `y` · `active` · `item{id,count}`。
- **`x` / `y` 永远是「容器内相对坐标」**（`Slot.x` / `Slot.y`，public final）。绝对屏幕坐标要靠容器原点
  `leftPos`/`topPos` 相加，而原点在 26.3 是 protected 且无公开 accessor。
- 将来若引入 access widener / access transformer，**只追加 `screenX` / `screenY`，绝不改写 `x` / `y` 的含义**
  —— 否则同一个字段名前后代表两种坐标，是典型的静默变更。
- 实测：`InventoryMenu` 46 格，`#0` 是合成结果位 (154,28)，`#45` 是副手 (77,62)、`containerSlot=40`。
| `lab/shared/src/main/java/com/mcdev/probe/ProbeServer.java` | 678 行（+`route()` 注册表 + `/status.capabilities`） |
| `lab/fabric/src/client/java/com/mcdev/probe/fabric/McdevProbeClient.java` | 32 行，`ClientModInitializer` |
| `lab/fabric/build.gradle` | +`sourceSets.client.java.srcDirs += file('../shared/src/client/java')` |
| `lab/cache/` ↔ `~/.gradle/caches/fabric-loom/assets/` | 资产从 HMCL 复用，见环境表 |

**P3 的四条实测结论**：

1. **`Minecraft.screen` 确实没了，但 `Screen` 在 `Gui` 里** —— `Minecraft.gui` 是
   `public final Gui`，`Gui` 提供 `screen()` / `setScreen(Screen)` / `overlay()`。
   ⇒ `/ui/snapshot|click|hover|hover_slot|close` 五条**全部可实现**（早先判的「无实现路径」是错的）。
   点击要走到 `AbstractWidget.onClick(MouseButtonEvent, boolean)` —— 只调 `Screen.mouseClicked`
   返回 `true` 但界面不动。
2. **`/player/inventory` 不需要 GUI** —— 直接读 `mc.player.containerMenu` 反而更稳（`slots:46`）。
3. **客户端探针的加载器耦合是零** —— 11 个 `net.minecraft.client.*` import，
   `net.minecraftforge.client.*` / `net.neoforged.neoforge.client.*` / `net.fabricmc.*` 全 0 处。
   三家共用一份 `ClientProbeServer`，只有入口分叉。
4. **26.3 客户端换到 SDL 了** —— `Window.getWindow()` 删除、`Window.handle()` 返回 `long`、
   事件类是 `org.lwjgl.sdl.SDL_Event`，不再是 GLFW。任何碰原生窗口的写法都要重做。

## 下一步：P4 玩家操作全集（8–12 天）

**这是 P4 的完整入口**，不要从 P3 重来。

### P4 第 0 步 ✅ 已完成（2026-10-19 18:50，记录见 [p4s0-scaffolding-removal-record.md](p4s0-scaffolding-removal-record.md)）

拆掉 P2 的临时脚手架，探针已是**独立 mod**：三家 `modId=mcdev_probe` / `group=com.mcdev.probe`，
`com/example/**` 与 `Config.java` / `ExampleModClient.java` 全删，`NeoForgeGameTests` 迁进
`com.mcdev.probe.neoforge` 并由探针的 `@Mod` 构造器注册。

验收全部通过：三家 `clean build` 零警告 → jar 换名（`mcdev_probe-1.0.0.jar`，47,852 / 26,401 / 29,960 B）
→ 三家专用服起来 → `/status.loader` 分别 `fabric|forge|neoforge` → 15 路由矩阵**仍是 12×200 + 3×501**。

**遗留两条启动噪声**（都不是本次引入，处置方式已定）：`examplemod ... MISSING` 来自 `Server1/testworld`
的注册表 ID 残留（不删世界，留用户决定）；netty kqueue/epoll 报错 P2 就有（与探针无关）。

### P4 主体第一段 ✅ 已完成（2026-10-19 21:45，记录见 [p4a-action-layer-record.md](p4a-action-layer-record.md)）

12 条语义端点**全部在真客户端跑通**，每条都有「客户端返回 + 服务端独立核实」两份证据：

| 端点 | 实测证据（服务端侧） |
|---|---|
| `move`(walk) | 600ms 前进 2.55 格（≈步行速度 4.25 格/s），`Input` 驱动成立 |
| `move`(teleport) | 默认 gamerule `player_movement_check=true` 时远端被服务端拉回（`snappedBack` + hint）；关掉后 60 格一次到位（`horizontalError:0.0`，§10.2） |
| `move`(server-teleport) | 客户端调服务端探针 `/command tp` 代劳 → 56 格外 `applied:true, horizontalError:0.0`（全程 708ms），`/player/state` 复核位置真的变了（§11.4） |
| `mine` | `/world/block 11,72,-79` → `air`，`ticks:2` |
| `place` | `/world/block 13,72,-79` → `stone`，主手石 61 |
| `use`(block/entity/item) | `Success` / `Pass[]`（苹果不能喂僵尸）/ `Success` |
| `attack` | 僵尸血量 19.0 → 18.8077 |
| `pick` | 主手变 diamond_block |
| `inventory` | `set-selected`/`click`/`button`/`drop`/`close` 通；`creative-set` **501 + 证据**（§4.2） |
| `gamemode` | 服务端 `playerGameType=1`（creative） |
| `ability` | 服务端 abilities `flying:1b`（客户端必须同时置 `mayfly`） |
| `spectate` | `cameraEntity` 切到僵尸 |
| `respawn` | 死后 `alive:true, health:20.0`（读回要重新取 `player()`，§4.4） |
| `sleep` | `sleeping:true`，玩家 y=72.5625（床高） |

包覆盖面 **21 / 58 ≈ 36%**（不是架构 §8 验收里的 45/58）—— 差的部分是特殊方块 UI(7)、查询与管理(12)、
聊天(5)、容器剩余(8) 等**不属于这 12 个语义端点**的包，要新增端点才能覆盖，属下一段。

新增六个文件（共享 main 4 + 客户端 2，`ClientActionBackend` 1032 行）见记录 §1；`/status` 增
`actionBackend:"client"` 与 `actions[...12]`；新增 `/entity/list`（capability `entity`）。

**收尾补跑（记录 §9）**：新增服务端路由 `/player/state`（capability `player`，直接读 `ServerPlayer` 字段 ——
因为 `/command` 的 `result` 不是成功布尔）；新增可重跑套件 `tmp/p4a-action-suite.ps1`（15 用例，
断言全走服务端），连续多次 **15/15**，最后一次跑在最终部署的 jar 上。

**跨端第一条真链路（记录 §11）**：`POST /action/move` 新增 `mode:"server-teleport"` —— 客户端探针调
服务端探针 `/command tp` 代劳，再 `/player/state` 读回权威坐标；`/status` 增 `serverProbeUrl` 字段。
两个 HTTP 坑各修一行（`.proxy(NO_PROXY)` 与 `.version(HTTP_1_1)`，后者是 h2c 升级协商导致的 5s 超时）。
最终产物（三家 0 警告）：fabric **83,903 B** / forge **33,955 B** / neoforge **37,511 B**。

### P4 主体第二段 ✅ `ServerActionBackend`（2026-10-19 22:40 起，2026-10-20 00:40 扩到 9 动作，记录 §12 §13）

服务端做**它天然权威的九件事**，路由与客户端同名同信封（`data.backend:"server"`）：

| 动作 | 端点 | 实现 | 实测 |
|---|---|---|---|
| `teleport` | `POST /action/teleport {player,to,look?}` | `ServerPlayer.teleportTo(...)` | `before 11.5,72,-74.5 → after 20,70,-70`，`horizontalError:0.0` |
| `gamemode` | `POST /action/gamemode {player,mode}` | `setGameMode(GameType)` | `SURVIVAL → CREATIVE`，`applied:true` |
| `ability` | `POST /action/ability {player,flying?,invulnerable?}` | `Abilities` + `onUpdateAbilities()` | `flyingBefore:false → true` |
| `mine` | `POST /action/mine {player,pos,face}` | `ServerPlayerGameMode.destroyBlock(pos)` | `broken:true`，`/world/block` 变 `minecraft:air` |
| `place` | `POST /action/place {player,pos,face}` | `gameMode.useItemOn(player, level, held, hand, BlockHitResult)` | `placedAt:4,72,-74`，`changed:true` |
| `use` | `POST /action/use {player,target,pos?/entityId?}` | `useItemOn` / `interactOn` / `useItem` | `result:Success[...]` / `Pass[]` |
| `attack` | `POST /action/attack {player,entityId}` | `player.attack(entity)` | `19.0 → 18.06`，`damaged:true` |
| `pick` | `POST /action/pick {player,pos}` | `BlockState.getCloneItemStack(...)` + 放进选中槽 | 主手变 `minecraft:diamond_block` |
| `inventory` | `POST /action/inventory {player,op}` | `menu.clicked(...)` / `InventoryMenu` / `clearContent` | 10 个 op，见下 |

`inventory` 的 10 个 op：`give` / `clear` / `set-selected` / `click` / `slots`（观察）/ `drop` / `close` /
`button` / `trade` / `rename`。后四个是「玩家打不开 GUI，服务端能按同一 handler 代跑」的产物；
没有菜单/铁匠台时**结构化报错**，不假装成功。

**附带的真 bug（记录 §12.3）**：`GameType.byName(raw)` 的单参重载是 `byName(raw, SURVIVAL)`，
未知输入**静默返回 SURVIVAL** —— 修前 `{"mode":"nonsense"}` 会 200 且真把玩家切成生存。
新增 `lab/shared/src/main/java/com/mcdev/probe/action/GameTypes.java`（显式 switch，未知输入 400），
两个后端都改调它。

**两个可重跑套件**（都能独立跑，不依赖对方）：

| 套件 | 文件 | 用例 | 结果 |
|---|---|---:|---|
| 客户端动作 | `tmp/p4a-action-suite.ps1` | 19 | **PASS=19 FAIL=0**（`tmp/p4a-suite-result.log`） |
| 纯服务端动作 | `tmp/p4a-server-suite.ps1` | 20 | **PASS=20 FAIL=0**（`tmp/p4a-server-suite-result.log`） |

**三家一致**：`/status` 均 `actionBackend=server, actions=[teleport,gamemode,ability,mine,place,use,attack,pick,inventory]`，
15 路由矩阵均 `200=12 501=3`，`{"mode":"nonsense"}` 均 400，无玩家时均 404 `player not online: <any>`。

**四条「验证方法自身错」（记录 §13.4）**：`give` 会与同种物品合并（套件先 `clear`）；`set-selected`
菜单槽 36 ↔ 快捷栏 0 两套编号；NoAI 僵尸也算「附近有怪物」会静默阻止入睡（以床为中心 `distance=..16` 扫）；
**启动脚本 stdout 不能接管道**（被拉起的 java 服继承句柄 ⇒ `| Select-Object -Last 4` 永不返回，
表现成「后台跑了 9 分钟」）。

### P4 第二批 ✅ 聊天 / 告示牌 / 命令方块（2026-10-20 21:35，记录 §15）

用户要求「全补上」那批（原估 聊天 6 包 + 特殊方块 UI 7 包），落地为 **三个面、六条路由**：

| 面 | 客户端 | 服务端 | 实测 |
|---|---|---|---|
| 聊天 | `POST /chat/send {text}` | —（`/command` 本就等价） | `via:chat` → 服务端日志 `<Player584> mcdev chat ok`；`text:"/list"` → `via:command` |
| 告示牌 | `POST /block/sign/edit`、`GET /block/sign` | `GET /block/sign`、`POST {op:read\|open\|set}` | 客户端写四行 → 服务端回读一致，`hasMessage:true`；Forge/NeoForge 零玩家 `op:set` 也读回一致 |
| 命令方块 | `GET /block/command-block` | `GET`、`POST {op:read\|set\|run}` | Forge/NeoForge 零玩家：`set` → `run` → `successCount:1`，日志 `[@] 39012-cb-ok` / `[@] 39013-cb-ok` |

- **告示牌不是「发个包就行」**：`SignBlockEntity.updateSignText` 的守卫要求
  `player.getUUID().equals(getPlayerWhoMayEdit())`，而这个 UUID 只有右键开编辑界面才会设 ——
  第一版直接发 `ServerboundSignUpdatePacket` 的结果是 `sent:true` 而四行全空（静默拒绝）。
  修法：`/block/sign/edit` 先 `gameMode.useItemOn(...)` 模拟右键，再发包；服务端 `op=set` 走直写方块实体（无需玩家）。
- **新增 capability `block_extra`**（两家服务端 `/status.capabilities` 可见）。
- **套件扩到 25 例**：`tmp/p4a-action-suite.ps1` 第 4 节 6 条（聊天、聊天命令、写牌回读、命令方块写+读、跑、三条响亮失败）。
- **`/chat/suggest` 故意不开**：响应是异步推送，与本项目「同步返回结果」的端点契约不合，开了只能报假成功。
- 顺带修的真问题：客户端 `onClient()` 把游戏线程异常包成 `ExecutionException`，导致同类错误客户端 500、服务端 404
  （改为拆包原样抛回）；`setblock` 的 `result:0` 不是成功布尔（远距离未加载区块静默失败）。
### P4 第三批 ✅ 端到端编排脚本（2026-10-20 22:05 起，22:18 扩到 17 条 + 三家全绿，记录 §16）

一条命令跑完六个阶段，产出可归档的取证目录：

```powershell
pwsh -NoProfile -File .\scripts\devrun.ps1 -Family fabric -Scenario scenarios\actions.json
```

| 阶段 | 判据 |
|---|---|
| 1/6 准备 | 按 `-Family` 选目录/端口；核对 `mods/` 里的探针 jar |
| 2/6 起服务端 | `Start-Process java` + 轮询 `GET /status` 到 200（默认 240s） |
| 3/6 起客户端 | `Start-Process pwsh` 跑 `gradlew runClient` + 轮询客户端 `/status` |
| 4/6 连接 | `POST /connect?host&port` → 轮询 `/status.player` → 读玩家名 → `op` |
| 5/6 场景 | 逐条发请求，按 `expect{status,json 点路径}` 判定 PASS/FAIL（**错误码也是契约**） |
| 6/6 取证 | `summary.md`：结论、阶段耗时、用例表、两侧 `/status` 原文、两边日志尾巴 |

- 场景文件：`scenarios/smoke.json`（5 条，连通性最小集）、`scenarios/actions.json`（**17 条**，自建场景的
  mine/place/give/walk 端到端 —— 每条改变世界的断言都跟一条 `/world/block` 或 `/item/mainhand` 回读）。
- 场景语法两个补强：`path`/`body` 里的 **`{{player}}`** 占位符（玩家名每次启动都变，Fabric 是 `PlayerNNN`、
  Forge/NeoForge 是 `Dev`）、以及 `waitMs`（发请求前 sleep，用于 tp 之后等落地）。
- 服务端 `give` 增加可选 `slot`/`select`：`give` 原本是 `Inventory.add`（任意空位），在 Forge 那台留着旧
  测试物品的世界里会拿到 `rotten_flesh` 当主手 → `place` 静默失败。现在可直达快捷栏 0 并顺手选中。
- **三家实测（同一份场景）**：fabric `PASS=17 FAIL=0`（22:13–22:14，jar 115,935 B）、forge `17/17`
  （22:14–22:15，112,184 B）、neoforge `17/17`（22:16–22:18，115,739 B），退出码均 0。
- **第一次跑就抓到真 bug**：`-Dmcdev.probe.port` 没传给服 → 探针监听默认 39001、脚本按 39011 等 240s 超时。
  修法 `$javaArgs = @("-Dmcdev.probe.port=…","-Dmcdev.probe.token=…") + $FamilyCfg.Args`（`-D` 必须在 `-jar`/`@args` 之前）。
- 起服/起端一律 `Start-Process -RedirectStandardOutput <file>`：**接管道或 `>` 重定向都会「看起来卡死」**，
  因为 java 继承了父进程的 stdout 句柄（记录 §16.9）。
- 收尾（`finally`）无条件执行：客户端 `/quit`、服务端 `/command stop`、按端口等释放（超时只告警不抛错）。

### P4 剩余（下一段入口）

| 项 | 状态 |
|---|---|
| `ServerActionBackend`（服务端侧观察 + 容器操作） | ✅ 2026-10-20 00:40（**9 动作 + 10 个 inventory op**，记录 §12 §13） |
| ~~「纯服务端跑动作」~~ | ✅ **已达成，且没用 FakePlayer**：对**在线玩家**直接调 `ServerPlayerGameMode` / `Player.attack`。FakePlayer 仍是可选增强（无客户端 CI 场景才需要） |
| Forge / NeoForge 客户端入口（`@Mod` 构造器判 `FMLEnvironment.dist == Dist.CLIENT`） | ✅ **三家真机 19/19**（2026-10-19 21:20）：两家 `build.gradle` 并进 `shared/src/client/java` + dist 守卫（NeoForge 用 `ClientStartedEvent`，Forge 用首个 `ClientTickEvent.Post`），三家 `runClient` 各跑一遍 19 条套件全绿，记录 §14.7 |
| ~~聊天 / 特殊方块 UI 那批（第二批端点）~~ | ✅ 2026-10-20 21:35：客户端 4 条 + 服务端 2 条，capability `block_extra`；三家客户端 25/25，记录 §15 |
| 端到端编排（六步：起服→起客户端→连接→操作→取证→收尾） | ✅ **脚本完成**（`scripts/devrun.ps1` + `scenarios/{smoke,actions}.json`，**三家各 17/17**，记录 §16）；矩阵模式 `-Family all` 三家串行汇总（22:19–22:23 全绿，记录 §17.1）；DSH 插件层 ✅ 已写好并通过自测（**未安装**：装进去要重启 DSH，留给用户拍板，见下节） |
| P5 打包（交付物） | ✅ **v2：发布物是一个套件**（用户 2026-10-20 22:40 定「作为整体一个套件发布，不需要多个 jar」）。`scripts/package.ps1` 产出 `dist/mcdev-probe-suite-1.0.0.zip`（**371,888 B / 15 条目**：`mods/` 三份 jar + `scripts/install.ps1` 入口 + DSH 插件 + `scenarios/` + README/LICENSE/SHA256SUMS/manifest），zip 复验 + 13 个文件逐个校验和全过，套件内 jar 与三家真机验证件 **sha256 完全相同**；解压后三个用例（正常装 / 目标不存在 / jar 篡改）全按预期 ⇒ 记录 §18。GitHub Release 形式=一个 tag + 一个 zip 附件（具体渠道待定） |

**验收标准**（原样保留）：58 个 serverbound 包覆盖面 ≥45；`/action/move` 在客户端侧能让玩家真的走起来
（`net.minecraft.world.entity.player.Input` 是 7 boolean 的 record）——**后半句已达成；前半句按「按动作需要覆盖」重新评定：累计 24/58，且已明确不追求按包凑数**。

### P5 打包 v2：**一个套件**（2026-10-20 22:40，记录 §18）

```powershell
pwsh -NoProfile -File scripts\package.ps1            # 重建 + 核对 + 组装 + 打 zip
pwsh -NoProfile -File scripts\package.ps1 -SkipBuild # 复用现有 jar（核对照做）
```

发布物只有一个：`dist/mcdev-probe-suite-1.0.0.zip`（371,888 B / 15 条目）。解开后跑一个入口：

```powershell
pwsh -NoProfile -File .\scripts\install.ps1 -Loader fabric -Target "<MC_ROOT>\Server" [-DshPlugin] [-DryRun]
```

套件内容：`mods/`（三份 jar，字节与三家真机验证件一致）· `scripts/install.ps1`（唯一入口，带 sha256 校验）·
`scripts/install-dsh-plugin.ps1` · `scripts/devrun.ps1` + `scenarios/` · `dsh-plugin/mcdev-devrun/` ·
`README.md` / `LICENSE` / `SHA256SUMS.txt` / `manifest.json`（`kind:"suite"`, `entrypoint:"scripts/install.ps1"`）。

核对链：构建 → 零警告 → 类数基线（19/19/21）→ jar 内元数据 → zip 必需文件 → 逐文件校验和。
**不做「一个 jar 通吃三家」**：三家入口与元数据文件由加载器各自解析，合并只能靠 jar-in-jar/loader 探测，收益为零风险不小。
旧 `dist/mcdev-probe-1.0.0/`（v1 扁平三 jar）不再是发布物，只留作对比证据。
**先读**：feasibility §7.5（58 个包的权威口径）、architecture §8（动作层三段式前置校验）。

### DSH 插件层 🟡 已写好、**未安装**（2026-10-20 22:30）

把编排脚本包成两个 DSH 工具，**薄包装**：编排逻辑只在 `scripts/devrun.ps1`，插件不重复实现（否则两处会分叉）。

| 文件 | 作用 |
|---|---|
| `tools/dsh-plugin/mcdev-devrun/dsh.mjs` | `export name/inject/apply`；注册 `mcdev_devrun`（family 枚举含 `all`、scenario、mods、keepRunning、timeoutMin）与 `mcdev_scenarios`（列 `scenarios/*.json`）。根目录解析顺序 `MCDEV_ROOT` → `<REPO>` → 从 `exec.cwd` 往上找 `scripts/devrun.ps1`，都不中则**明确报错不猜** |
| `tools/dsh-plugin/selftest.mjs` | 不需要 DSH：动态 import 插件 + 桩 ctx 调 `apply()`，断言两个工具名、schema 形状、`output.render`、family 枚举，并真跑一次 `mcdev_scenarios` 列目录 ⇒ **SELFTEST PASS** |
| `scripts/install-dsh-plugin.ps1` | `-Mode install|uninstall|dryrun`（默认 install）。复制插件到 `$DSH_HOME/plugins/mcdev-devrun`、往 `$DSH_HOME/cordis.patch.yml` 追加带注释的 `- insert:`；**改前备份** `cordis.patch.yml.bak-<时间戳>`；**幂等**（已有 `id: mcdev-devrun` 就只更新文件） |

验证（不碰真 DSH）：在 `tmp/dsh-home-test`（真实挂载表前 60 行的副本）上跑完整三段 —— 首次 install ⇒ id 出现 **1** 次且插件文件到位；再 install ⇒ 仍 **1** 次（幂等）；uninstall ⇒ id **0** 次且插件目录被删。真实 home 的 `已挂载 = False`，挂载表尾部未变。

```powershell
node tools\dsh-plugin\selftest.mjs                                  # 自测（无需重启）
pwsh -NoProfile -File scripts\install-dsh-plugin.ps1 -Mode dryrun   # 只看要做什么
pwsh -NoProfile -File scripts\install-dsh-plugin.ps1                # 装（**之后重启 DSH 才生效**）
pwsh -NoProfile -File scripts\install-dsh-plugin.ps1 -Mode uninstall
```

**为什么没直接装**：配置只在 DSH 加载时读取，装了必须重启；重启会断当前会话，且插件若在注册阶段抛错会影响启动（有 safe mode 与 undo 快照兜底，但仍不该由助手替用户决定重启时机）。回滚：`-Mode uninstall`，或把 `cordis.patch.yml.bak-*` 覆盖回去。

### 新环境部署 + 网络形态（2026-10-19 22:57，手册 [deployment-topology.md](deployment-topology.md)）

三件事已落地：

1. **套件纯度断言**（`scripts/package.ps1`）：套件里出现非 `mcdev-probe-<v>-<loader>.jar` 的 jar、
   或 `libraries|assets|versions|runtime|world` 目录、或解压后 >5 MB ⇒ **打包直接失败**。
   当前套件 `dist/mcdev-probe-suite-1.0.0.zip` **377,524 B / 16 条目 / 14 文件**，校验和全过。
   踩过的坑：内层 `Where-Object` 的 `$_` 会遮蔽外层，导致三个探针 jar 全被判成「非探针 jar」⇒ 先把名字存 `$n`。
2. **`scripts/provision-instance.ps1`**：三种网络形态 —— 直连 / `-Proxy`（或 `HTTPS_PROXY`、Windows 系统代理）/
   `-Offline`（只允许 `-CopyFrom`，一个请求都不发）。`-CheckOnly` 先探 7 个端点（≈11 s），失败给可执行指引；
   `-Mirror` 走 BMCLAPI 镜像（Fabric）；Forge/NeoForge 安装器需要代理时自动转成 `JAVA_TOOL_OPTIONS` 的
   `-Dhttps.proxyHost/Port` + `nonProxyHosts`。**实测同一台机**：直连 6/7（`maven.neoforged.net` 10 s 超时），
   走 `127.0.0.1:<PROXY_PORT>` **7/7 全通**。复制完成会写 `eula.txt`、改写 `server.properties`（端口/离线/白名单/
   gamemode/level-name）、生成 `run.ps1`、登记 `instances/<name>.json`。
   踩过的坑：`$Target` 没转绝对路径 ⇒ `run.ps1` 里 `Set-Location 'tmp\…'` 换个 cwd 就废了（已修）。
3. **启动器无关的实例登记 + 纯服务端场景**：`devrun.ps1 -Instance <name>`；`client.mode = gradle|external|none`；
   `side=server` 的实例跳过客户端与连接两阶段，配合 `scenarios/server-only.json`（5 步，用 `/command` +
   `/world/block` 证明观察能力，并断言「无人在线时动作路由回 404 PLAYER_NOT_FOUND」）。实测 17 s 跑完。

**代理要注入到四个地方**（漏一个就卡在那一环）：PowerShell 下载（`-Proxy`）、Java（`JAVA_TOOL_OPTIONS` 四件套）、
Gradle（同上或 `systemProp.*`）、外部客户端实例（启动器 JVM 参数）。

## 待测用例（真实 mod 场景，用户 2026-10-20 指定「先记下来之后要测」）

| 用例 | 目标 mod | 要点 | 状态 |
|---|---|---|---|
| **门框内贴牌 + 写字** | PairedPortals | 黑曜石传送门框**内**贴一块告示牌并写入文字（PairedPortals 的功能实现依赖它）。路径：`/action/inventory {op:give,item:oak_sign}` + `{op:set-selected}` → `/action/teleport` 到框旁 ~4 格 → `/action/place {pos:<框块>, face:"east"}`（点黑曜石朝门内那一面，牌落在框内那块空气上）→ `/block/sign/edit {pos, lines}` → 服务端 `GET /block/sign` 回读。 | 待测（能力已就绪：`place` 与 `sign/edit` 分别真机验证过） |

注意事项：① 门内若是已激活的 `nether_portal` 方块（非空气）要先破掉或让 mod 以未激活框架生成；
② 牌占住门内位置本身会打断门，贴牌/激活的顺序要跟着 mod 的检测逻辑试；③ `place` **不走准星**（自己构造
`BlockHitResult`），只受服务端距离校验；④ 若 mod 依赖「玩家真写牌」这条路径，必须用客户端 `/block/sign/edit`
（服务端 `op=set` 直写方块实体，不发 `ServerboundSignUpdatePacket`）。
## 三套骨架的现状（都已 build + runServer + 探针 200）

| 项目 | 目录 | 产物 | 首次构建基线 | 增量构建 | 状态 |
|---|---|---|---|---|---|
| Fabric | `lab/fabric` | `mcdev_probe-1.0.0.jar` **115,935 B**（19 类） | 3m 13s | 2–4s（`compileClientJava` ~7s） | **服务端 + 客户端 + 动作层 + 服务端后端 + 客户端套件 25/25** |
| Forge | `lab/forge` | `mcdev_probe-1.0.0.jar` **112,184 B**（19 类） | 4m 44s（mavenizer 4m 09s） | 3s | 服务端 + 9 动作 + **客户端入口（runClient 25/25）** |
| NeoForge | `lab/neoforge` | `mcdev_probe-1.0.0.jar` **115,739 B**（21 类） | 4m 53s（NeoForm 162s） | 2s | 服务端 + 9 动作 + **客户端入口（runClient 25/25）** |

> Forge 的增量构建有个坑（记录 §12.5）：`clean build` 一次跑完时 jar 可能只含**本轮改过的类**
> （实测 12,791 B / 3 类，但 `BUILD SUCCESSFUL`）。用 `--rerun-tasks`，并且每次构建后核对类数：
> **fabric 19 / forge 19 / neoforge 21**（接客户端源集后每家 +4 类；Fabric 早在 P3 就有客户端源集）。

三份 `build.gradle` 都有 `sourceSets.main.java.srcDirs += file('../shared/src/main/java')`；
**三家都接了客户端源集**（`lab/fabric` 是 `splitEnvironmentSourceSets()` 的独立 client 源集，
另两家把 `../shared/src/client/java` 并进 main source set，端边界靠 `dist` 守卫 —— 记录 §14.1 §14.3）。

## 环境（换机必读，每一条都是踩过的坑）

| 项 | 值 |
|---|---|
| JDK 25 | `<JAVA_HOME>`（Temurin 25.0.4.1+1 LTS，GPLv2+CE，可分发） |
| 构建时必须 `JAVA_HOME=<JAVA_HOME>` | PATH 上的 `java` 是 21，`JAVA_HOME` 默认指向 GraalVM（与 PATH 不一致） |
| **gradle 必须走代理** | `JAVA_TOOL_OPTIONS=-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=<PROXY_PORT> -Dhttp.proxyHost=…`（verge-mihomo 的 HTTP 口）。不走代理时 `maven.neoforged.net` 的 Java TLS 会被掐断，**`hosts` 钉 IP 无效**（已撤除，备份 `lab/hosts.bak.*`） |
| `gradlew.bat` 传 `-D` 会被 PowerShell 吃掉 | 用 `JAVA_TOOL_OPTIONS` 或 `GRADLE_OPTS` |
| gradle 发行版 | 走腾讯镜像 `mirrors.cloud.tencent.com/gradle/` 手工预置 wrapper 缓存；官方域名 307 到 GitHub 拉不动 |
| ForgeGradle 的 JDK 8 | 已预置在 `~/.gradle/caches/minecraftforge/forgegradle/mavenizer/caches/`（清华 Adoptium 镜像取的 `8u504b01`） |
| **`runClient` 首次要下载 1.8 GB 资产** | 走代理只有 ~190 KB/s（1 小时）。已把 `<HMCL_HOME>\assets\objects`（10,367 文件 / 1,873.7 MB）robocopy 进 `~/.gradle/caches/fabric-loom/assets/objects`，索引同一份 |
| **专用服不能用后台 job 起** | harness 的 pwsh 后台 job 关闭 stdin ⇒ MC 服务端读到 EOF 立即退出（exit 1）。用 `Start-Process -WindowStyle Hidden -PassThru` |
| **探针鉴权是请求头** | `X-Mcdev-Token: mcdev`，不是 query 参数；不带头 401 |
| **`white-list=true` 是 26.3 默认值** | 离线账号会被踢。三台测试服已改 `white-list=false` |
| 三台测试服 | `<MC_ROOT>\Server`(Fabric,25566) / `Server1`(Forge,25567) / `Server2`(NeoForge,25568)，`pause-when-empty-seconds=0` |
| 生产服 | `<MC_ROOT>\Server-26.3`（25565，96 mods）**全程未被改动** |
| 读 Forge/NeoForge 控制台日志 | 文件被 java 独占，须 `[IO.File]::Open($p,'Open','Read','ReadWrite')`；正文是 GBK，须 `[Text.Encoding]::GetEncoding(936)` |
| 探针端口 | 服务端 `-Dmcdev.probe.port=39011/39012/39013`（默认 39001）；客户端 `-Dmcdev.probe.client.port`（默认 39002） |
| Fabric 测试实例的 fabric-api 历史缓存 | `.fabric/processedMods/` 里有 remapped 的 fabric-api，**清空 `mods/` 也能起**。这是历史缓存不是产品行为 |

## 四条已定但容易被忘的约束

1. **版本范围**：本期只做 26.3；项目级声明范围是 26.1.2 ~ 26.3，但 26.1/26.2 不进任何里程碑。1.21.11 及更早是硬墙（重映射体系，等于另做一条版本线）。
2. **模式 C**：`main` 永远等于最新受支持版本，旧版本走保护分支。当前只有 `main`，没有可回迁目标。
3. **探针自身必须声明为「单端可选」**，否则「客户端不装探针、只装被测 MOD」这类测试永远做不了。
4. **探针是「开发期外挂组件」，不是用户 MOD 的一部分**（用户 2026-10-19 定）—— 探针必须能作为**独立 mod** 丢进 `mods/`，开发完直接删掉，生产环境里不存在。⇒ 禁止把探针代码/`install()` 写进被测 MOD 的源码或构造器。P2 里为了让骨架跑通写过 `ExampleMod` 构造器调 `install()`，**那是临时脚手架，P5 打包前必须拆掉**（探针自己的 `@Mod("mcdev_probe")` 才是产品形态）。

## 未决（不阻塞，但迟早要定）

- ~~**`/ui/*` 怎么办**~~ —— **已解决（2026-10-19 13:35 实测）**：`Gui.screen()` 拿得到当前界面，
  五条路由全部实现并跑通（`/ui/close` 把 AccessibilityOnboardingScreen 关成 TitleScreen，
  `/ui/click` 把 TitleScreen 点成 OptionsScreen）。**剩下的唯一缺口**是
  `AbstractContainerScreen.hoveredSlot` 是 protected 且无公开 accessor ⇒ `/ui/hover_slot`
  只返回 `menu` 名 + `slots` 数量 + `hoverDispatched`，不给「悬停在哪一格」。
- **`/ui/*` 在 26.1/26.2 是否也一样** —— 26.3 的 `Gui.screen()` 是新位置，26.1/26.2 未核对；
  若旧版放在 `Minecraft` 上，`capabilities` 要按版本派生，而不是按端派生。
- ~~**fabric-api 依赖留不留**~~ —— **已定：留（用户 2026-10-19）**。理由：Fabric 生态里 fabric-api 事实上是标配，依赖它比自建 loader 原生命令注册便宜得多。执行上必须做对一件事：`fabric.mod.json` 里写 `depends: { "fabric-api": "*" }`，让它缺了**启动即报 `HARD_DEP_NO_CANDIDATE`**，而不是运行到一半 `ClassNotFoundException`（P1 已实测过这个报错形态）。注：fabric-api **不在** loader 内，是独立 mod，所以这条依赖必须显式声明。
- ~~**Forge / NeoForge 的客户端入口**~~ —— **已落地（2026-10-20 20:45，编译级）**：`@Mod` 构造器里判 `FMLEnvironment.dist == Dist.CLIENT`，NeoForge 用 `ClientStartedEvent`、Forge 用首个 `ClientTickEvent.Post`（EventBus 7 没有 `@Mod.EventBusSubscriber`）。**未做的部分**：两家的 `runClient` 实跑（首次可能触发资源下载），记录 §14.6。
- ~~**`/action/move` 的远端瞬移**~~ —— **已解决（2026-10-19 19:45，用户 m01211 批准）**：新增
  `mode:"server-teleport"`，客户端探针调服务端探针 `/command tp` 代劳，再用 `/player/state` 读回权威坐标。
  56 格外实测 `applied:true, horizontalError:0.0`（708ms）。配置面是 `-Dmcdev.probe.server.url`
  （默认 `http://127.0.0.1:39001`）；跨端编排因此有了第一条真链路（记录 §11）。
- **`creative-set` 为什么被丢** —— 客户端 `hasInfiniteMaterials`/feature flag 与服务端 handler 守卫都通过，
  包仍被丢弃；`/command give` 正常。若要根因，得抓服务端 `handleSetCreativeModeSlot` 调用日志。
- **58 包覆盖 21/58** —— 差的 37 个包（特殊方块 UI / 查询管理 / 聊天 / 容器剩余 / 骑乘）需要新增语义端点，
  不是现有 12 条能摊出来的；是否为了覆盖率先扩端点，需用户定优先级。
- 公开发布形态：DSH 插件 + 三套探针 jar 是给 DSH 用户用的；如果目标受众是不用 DSH 的 MC modder，HTTP 接口才是产品边界（架构上已隔离好，但发布物料要另做）

