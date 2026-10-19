# PairedPortals × 本套件：全自动化可测性审计

对象：`pairedportals-1.0.0.jar`（Fabric 26.3，服务端 mod，`environment: "*"`，无 client 入口）。
问题：它的每一个功能，能不能由本套件（服务端探针 + 客户端探针 + `devrun` 场景）**全自动**驱动并断言？

结论先说（证据在下面各节）：

| 判定 | 数量 | 说明 |
|---|---|---|
| 全自动可测 | 6 组 / 覆盖 38 项功能里的 33 项 | 点火、配对、穿越、拆除、重建、跨维度、持久化、命令族、日志语义全部可驱动可断言 |
| 半自动（要抓图或降级判据） | 1 组 | 紫屏 / 落点观感 / 生物群系，`/screenshot` 能拍到但没有像素断言 |
| 必须人眼 | 4 类 | 音效、actionbar 是否真显示、紫屏动画、F3 读数观感 |
| 套件缺口（需补代码才能全自动） | 6 条 | 见 §5：G1/G2/G3/G4/G6 待做，G5 已修并复跑验证 |

## 1. 被测方的可观测面

PairedPortals 是纯服务端 mod（`fabric.mod.json` 只有 `main` 入口），所有状态都能从服务端读到：

| 可观测面 | 具体形态 | 本套件怎么拿 |
|---|---|---|
| 门洞方块 | 门洞 = 原版 `minecraft:nether_portal`，框 = 原版黑曜石 | `GET /world/block?x&y&z&dim=`（服务端探针**支持 dim 参数**） |
| 告示牌文字 | 配对键 = 正面四行拼接 | `GET /block/sign`；写入用 `POST /block/sign op=set`，或用客户端 `/block/sign/edit` 走真实发包路径 |
| 注册表 | `SavedData`（`<world>/data/pairedportals/portals.dat`），全局一份 | `POST /command {"cmd":"pairedportals list"}` + `GET /log/tail`（**实测通过**：回执以 `System chat: <原文>` 进 log4j 环形缓冲，`0 portal(s): 0 bound pair(s) …` 这种头行原样可读） |
| 单门全字段 | id / 维度 / 门洞角 / 尺寸 / 状态 / 牌文字 / 对门 / 对门世界状态 | `POST /command {"cmd":"pairedportals info <uuid|pos>"}` + `/log/tail` |
| 决策回放 | 只读重放该位置的点火判定（不动世界） | `POST /command {"cmd":"pairedportals debug <pos>"}` + `/log/tail` |
| 事件日志 | 全英文、前缀 `[pairedportals]` | `GET /log/tail?n=`（探针内 log4j RingAppender） |
| 实体位置/维度 | 谁穿过去了、落在哪 | `GET /entity/list`（服务端）+ 客户端 `/status` 的 `dimension` |
| 玩家维度 | 玩家进了哪个维度 | 客户端 `GET /status` → `dimension` / `playerPos` |
| 时间 | 冷却 300 tick、20 秒不回弹 | `POST /command {"cmd":"mcdev freeze true"}` + `step <N>`（转发 `TickRateManager`，与 `/tick` 同一套状态） |

要点：**玩家可见的 actionbar 文案不进日志**，有玩家时只有客户端能看到；无玩家（8 格内无人）时 `FrameNameHint` 会把同一句英文写进日志并带 `(no player nearby)` —— 这是唯一能把「拒绝点火 / 未绑定 / 已绑定」文案变成断言的钩子。

## 2. 驱动手段对照

| 要做的动作 | 全自动驱动方式 | 备注 |
|---|---|---|
| 砌门框 | `POST /command {"cmd":"fill ... obsidian"}` | 命令回执进日志 |
| 放牌子并写字 | 服务端 `POST /block/sign op=set`（直写 `SignText`）或客户端 `/block/sign/edit`（真实发包） | 要验「牌子被消耗」走后者更真 |
| 点火 | `POST /action/use {"target":"block","pos":...,"face":"up"}`（持打火石，走 `gameMode.useItemOn`）或 `{"cmd":"setblock <pos> fire"}` | 后者与 mod 自己的 smoke 脚本同路径，同 tick |
| 玩家走进门洞 | 客户端 `/action/move` 或 `/action/teleport`（同维度） | 服务端 `/action/teleport` **不能换维度**（只 `p.teleportTo` 当前 level） |
| 实体过门 | `{"cmd":"summon <type> <x> <y> <z>"}` 落在门洞里 | 非玩家实体传送延迟 0 tick，别先 summon 再 ride |
| 拆框 | `POST /world/break`（仅主世界）或 `{"cmd":"setblock ... air"}`（任意维度） | `/world/break|mine` 硬写 `mc.overworld()` |
| 读对门（跨维度） | `GET /world/block?...&dim=minecraft:the_nether` | |
| 断言命令回执 | `POST /command` → `GET /log/tail` | `/command` 只回 `{cmd,result:0}`，**不回文本** |
| 确定性等待 | `mcdev freeze true` → `step N` → 读状态 | 场景 `waitMs` 在**发请求前** sleep，且没有轮询/重试 |

## 3. 功能 × 可测性矩阵

> 「驱动」= 用什么把它跑起来；「断言」= 判据落在哪。判定：✅ 全自动 / 🟡 半自动 / ❌ 必须人眼。

| # | 功能组 | 驱动 | 断言 | 判定 |
|---|---|---|---|---|
| 1 | 点火建立自定义门（打火石 / `setblock fire` / 发射器同路径） | `/action/use` 或 `/command setblock fire` | 门洞方块变 `nether_portal`（`/world/block`）+ 日志 `[pairedportals] … built/paired/portal at` | ✅ |
| 2 | 门名 = 告示牌正面四行（拼接、trim、区分大小写） | `/block/sign op=set`（含空格/大小写变体） | `/block/sign` 读回 + `info` 的 `binding text:` + 配对行为（同文合、异文不合） | ✅ |
| 3 | 告示牌数量/位置规则（恰好一块有内容的、框外牌忽略） | 放 0/1/2 块牌分别点火 | 点亮与否 + 日志文案（无玩家时英文进日志） | ✅ |
| 4 | 无牌 ⇒ 完整交还原版 | 无牌点火 | 门洞是原版 `nether_portal` 且注册表为 0（`list` 计数） | ✅ |
| 5 | 拒绝点火（空牌 / 两块牌 / 无权限）三类文案 | 同上 + `permission none` | 日志 `the sign in the frame is blank` / `only one written sign fits in the frame` / `no permission to light a custom portal (currently: …)`（8 格内无人时） | 🟡 有玩家时只有 actionbar ⇒ 必须人眼；无玩家路径 ✅ |
| 6 | 同文字 FIFO 配对 | 先后点两扇同文字门 | `list` 头行 `N portal(s): P bound pair(s) … L door(s) waiting` + 两门都是 `BOUND`（`info`） | ✅ |
| 7 | PENDING → BOUND 状态迁移 | 同上 | `info` 的 `state: PENDING/BOUND`、`binding text: … (paired…)` | ✅ |
| 8 | 隔离性（不同文字互不传送） | 两扇异文门，实体进门 | 实体仍在原维度（`entity/list` + 客户端 `dimension`）+ 日志 `blocked travel` | ✅ |
| 9 | 未配对门 = 零反馈 | 实体走进 PENDING 门 | 日志 `blocked travel`；无 travel 行 | 🟡 零反馈里「没有 actionbar」这一半靠❌人眼（可退化为「日志里没有 travel」） |
| 10 | 跨维度传送（主世界↔下界；末地同路径） | 实体进门 + `step` | 日志 `travel: … -> … (minecraft:the_nether)` + 对门门洞里出现该实体（`entity/list` / `execute in … if entity`）+ 落点坐标 | ✅ |
| 11 | 同维度传送 | 同维度两门配对 | 同上 + `both doors are in <dim> — a same-dimension pair works like any other` | ✅ |
| 12 | 落点公式（3 宽→2 宽三格铺开） | 三个不同横向站位各走一次 | 落点坐标（`entity/list` 的 pos 或日志） | ✅ |
| 13 | 出站朝向（同轴 0°、跨轴 +90°） | 实体带 `Rotation:[180f,0f]` 进门 | `data get entity`（走 `/command`）或日志 | ✅ |
| 14 | 冷却 / 不回弹（300 tick；载玩家 10 tick） | 抵达后 `step 400`/等 20 s 再读 | 维度未变 + 只有一条 travel | ✅（`freeze`+`step` 后可完全确定） |
| 15 | 实体/物品/载具/乘客过门 | summon 六类 | 落点坐标 + 乘客无自己 travel 行 | ✅ |
| 16 | 不新增方块/物品/实体/贴图/音效 | — | 静态断言 jar 内 `client|assets|lang|models|textures` 零命中；`fabric.mod.json` `environment:"*"` | ✅（离线检查，不需要跑服） |
| 17 | 门几何校验（四条边 + 门洞全亮；斜角不管） | 挖斜角 / 挖贴洞方块各一次 | 门洞是否仍亮 + 注册表计数 | ✅ |
| 18 | 共用柱归属（拆 A 不牵连 B） | 两框相隔三格 | B 仍亮仍注册 + 共用柱方块仍在 | ✅ |
| 19 | 拆一端 ⇒ 整对消失 | 挖贴洞方块 | 两端门洞都变空气 + 对门框完好 + `0 portal(s)` + 日志 `no longer matches the world; retiring it` / `went down with its twin` | ✅ |
| 20 | 重建/重燃（补框放同文字牌） | 补框后重点火 | 计数序列 `1 2 0 1 2` | ✅ |
| 21 | 巡检 sweep / `purge` | 等巡检或 `/pairedportals purge` | 日志 `sweep checked N loaded portal(s)`、`sweep retired N portal(s)` | ✅ |
| 22 | 重启持久化 | 停服再起（同世界） | 启动日志 `loaded N portal(s) from the world registry`；全新世界只有 `sweep checked 0 loaded portal(s)` | ✅（需两次 `devrun`） |
| 23-24 | `help` / `list [bound|unbound]` | `/command` | 返回码 + `/log/tail`（头行 + 每门一行） | ✅ |
| 25 | `info <uuid|pos>` | `/command` | 各字段行 + 未命中 `no portal owns …` + 返回码 1/0 | ✅ |
| 26 | `delete`（连带对门） | `/command` | 两端门洞清空 + 日志 `took its twin … down with it` | ✅ |
| 27 | `tp <uuid|pos>`（必须玩家） | `/command`（玩家在线时由控制台执行同一条） | 客户端 `playerPos` 落在门前一格、不在门洞 | ✅ |
| 28 | `unbind` / `pair` | `/command` | 两门退回 PENDING / 重新 BOUND（`info`） | ✅ |
| 29 | `permission all|op|none` | `/command` + 点火者是否为 OP | 拒绝/放行 + 文案（无玩家时进日志） | ✅ |
| 30 | `debug <pos>` | `/command` | `ignition probe at …; vanilla would light it: …; real signs inside it: N[…]` | ✅ |
| 31 | 准星门框名字提示（actionbar，5 格、20 tick 重发） | 客户端 `/ui/hover` 或 `/player/use` 前的准星状态 | **没有数据通道** | ❌ 人眼（无玩家时退化为日志） |
| 32 | 告示牌遮挡（点火时把牌读成空气） | 点火时牌位有牌 | 记录里 `masked` 区分「真空洞」与「靠遮挡走通」→ `info` | ✅（间接，够用） |
| 33 | 音效 / 紫屏 | 玩家过门 | 音频无通道；紫屏可 `/screenshot` | ❌ 音效必须人眼；紫屏 🟡 抓图 |
| 34 | 原版门隔离（旁边有原版门不被抢） | 三段布局各走一次 | 原版门走原版落点（坐标）+ `grep -c "vanilla's portal search found our door"` ≥1 | ✅ |
| 35 | 跨世界 Worlds 三种「对面不在」 | 需装 Worlds | `list` 行尾 `[loaded] / [registered, not loaded] / [no such world]` + `info` 的 `twin world:` + 日志 `cannot travel: …` | ✅（依赖第三方 mod；本套件不代装） |
| 36 | 语言 zh/en | — | 服务端日志恒英文、控制台输出恒 EN ⇒ 服务端侧只能测英文；zh 文案要客户端 `language` | 🟡 |
| 37 | 掉线/重连、多人 | 客户端 `/connect`、`/quit` | 二次连接后门仍可用 | ✅ |

## 4. 「必须人眼」的 4 类，为什么自动不了

1. **音效**：服务端没有音频数据流；客户端探针也没有声音捕获接口。
2. **actionbar 是否真显示**：服务端只发 `ClientboundSetActionBarTextPacket`，探针没有「读玩家最近收到的系统消息」的钩子。
3. **紫屏动画与加载过渡**：`/screenshot` 能拍到画面，但判据是「紫色遮罩出现过」这类像素级现象，没有图像断言。
4. **F3 读数、生物群系观感**：截图 + 人眼。

前三类的共同根因是「缺少客户端侧的消息/画面断言钩子」，不是 mod 不可测 —— 见 §5 的 G1。

## 5. 套件缺口（按「补了能多测什么」排序）

| # | 缺口 | 影响 | 补法（成本） |
|---|---|---|---|
| G1 | 客户端探针读不到玩家收到的 actionbar / 系统消息 | 5 类文案（未绑定/已绑定/无权限/空牌/两块牌）在有玩家时只能人眼；`state: PENDING/BOUND` 只能间接从 `info` 推 | 客户端加一条 `/chat/tail`：hook `ClientboundSetActionBarTextPacket` / 读 `Gui` 的 overlay message 字段，环形缓存最近 N 条（**中等**） |
| G2 | 服务端 `/player/state` 没有 `dimension` | 玩家跨维度只能靠客户端 `/status` 或日志 `travel:` 断言 | 加一个字段（**一行**） |
| G3 | `/world/break`、`/world/mine`、sign、command-block 硬写 `mc.overworld()` | 下界/末地的拆框只能用 `setblock air` 命令代替；真拆（带掉落、带邻居更新）测不到 | 全部改走 `dim` 参数（**小**，但动 5 处） |
| G4 | 场景 DSL 没有轮询/重试 | 传送后只能 `waitMs` 死等；慢机器上会假失败 | 加 `repeat` / `until`（**小**） |
| G5 | ~~`devrun` 的 gradle 客户端壳用相对取证路径~~ **已修并复跑验证** | 修复前：客户端壳启动即失败、无任何输出、外层干等 300 s | `$EvidenceDir` 在 `New-Item` 之后 `Resolve-Path` 成绝对路径；实测客户端 33 s 就绪、玩家进世界、`op` 成功 |
| G6 | **场景断言只能**精确等值，不能 grep/正则 | `/log/tail` 拿到的是 lines 数组，`expect.json` 的 `data.lines[0].message` 之类只能比对整行原文 ⇒ 「日志里出现过 `went down with its twin`」这种断言**写不进场景**，只能跑完看 `results.json` | 二选一：① `scripts/devrun.ps1` 给 step 加 `expect.log: {"contains": "..."}`；② 探针加 `GET /log/find?pattern=` 返回 `{count,matches}` 让场景直接断言 `data.count`（**小**） |

`/command` 不回文本（只能靠 `/log/tail`）不算缺口 —— 控制台回执确实进日志，用「唯一戳 + n 窗口」断言即可。

两条使用注意（不是缺陷，但会骗人）：

- `-Mods` 把 jar 复制进 `<ServerDir>\mods` 后**不清理**。跑完带 mod 的用例再跑不带 `-Mods` 的对照组时，被测 mod 还在，对照就不成立。跑完手动删，或者专门起一个只放探针的实例。
- 场景里 `waitMs` 在**发请求前** sleep，且没有轮询；`server-only.json` 这类给 `side=server` 实例写的契约用例在双端实例上必然 FAIL（有玩家时不会 404），别混用。

## 6. 建议的落地顺序

1. 先跑 **G5 修复后的客户端冒烟**：`devrun -Family fabric -Scenario scenarios\server-only.json -Mods <pairedportals.jar>` 必须真的进世界（`/status` 有 `dimension`），否则后面全是空中楼阁。
2. 按 §3 的 1→2→6→7→10→19→20→22 顺序写场景（点火、配对、穿越、拆门、重建、持久化），每条只断言**两个**面：门洞方块 + `list`/`info` 文本。这三样齐全时，用例就已经覆盖 mod 的绝大部分逻辑。
3. G1 做掉之后，§3 里所有 ❌ 文案类断言可以一起转成 ✅（音效除外）。
4. G2/G3/G4 属于「测试顺手就扩」的改动，不必为 PairedPortals 单独开一轮。

## 7. 已知的被测方文档不一致（写断言前必须核对源码）

| 分歧 | 结论 |
|---|---|
| `smoke-demolish.sh`：README 脚本表说「已覆盖挖斜角」，`docs/zh-CN/GAMEPLAY.md:202` 说只跑过 `bash -n` | 以源码行为为准，断言自己写 |
| `list` 头行：`docs/zh-CN/COMMANDS.md` 写 `2P door(s)`，`text/Messages.java:174` 是 `%d door(s)` | **以源码字符串为准**：`%d door(s)` |

## 附：本轮实测（本机，Fabric 26.3 / Java 25）

| 实验 | 命令 | 结果 |
|---|---|---|
| mod 能否被本套件加载 | `devrun -Family fabric -Mods <pairedportals-1.0.0.jar> -Scenario scenarios\server-only.json` | 服务端正常启动，日志 `pairedportals 1.0.0` 与 `[pairedportals] initialised`；**无需改 mod** |
| 客户端分支是否可用 | 同上（修复前） | ❌ 客户端壳启动即死、无 `client.out`，外层 300 s 超时（G5） |
| 同上（G5 修复后） | `-EvidenceDir <绝对路径>` | ✅ 客户端 33 s 就绪 → `/connect` 进世界（`player=Player<NNN>`）→ `op` 200 → 5 条场景 4 PASS |
| 控制台回执是否可断言 | 场景：`pairedportals help` / `list` / `mcdev status` + `GET /log/tail?n=60` | ✅ 全部以 `System chat: …` 进环形缓冲，实读到 `0 portal(s): 0 bound pair(s) (0 door(s)), 0 door(s) waiting for a partner; activation: all (everyone)`、`tick=697 players=1 frozen=false step=0`、以及 mod 自己的 `[pairedportals] loaded 0 portal(s) from the world registry` |
| `server-only` 场景在「双端实例」上 | 同上 | 1 条 FAIL：`loud-failure-no-player` 期望 404 `PLAYER_NOT_FOUND`，实得 200 —— **不是 bug**：`server-only.json` 是给 `side=server` 实例用的契约用例，双端实例有玩家在线时必然 200。写 PairedPortals 场景时别复用这条 |

## 附：下一步第一刀（建议）

先只做 G6（断言通道）+ 一个 `scenarios/pairedportals-core.json`：砌框 → 放牌写字 → 点火 → 断言门洞方块 + `list` 头行 + `info` 的 `state: PENDING` → 第二扇同文字门 → `state: BOUND` → 实体进门 → `travel:` 行。这一条跑通，就证明「整条自动化链路对第三方 mod 成立」，剩下的功能是按同一模板复制。
