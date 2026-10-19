# 探针系统架构设计

> 状态：设计定稿（先于代码）
> 前置文档：[mc26.3-multi-loader-probe-feasibility.md](./mc26.3-multi-loader-probe-feasibility.md)（生态事实与可行性证据）
> 本文档只谈结构，不含实现。所有数字标 `[估]` 的是估算，其余是实测或原代码事实。

---

## 0. 这份文档解决什么问题

用户要求：架构、功能模块、边界，以及**便于之后增删功能 / 维护**的东西。

一份架构文档如果只画目录树，它就没在回答这个问题。真正决定「好不好改」的是四件事：

1. **新增一个功能要碰几个文件** —— 越少越好
2. **删除一个功能会不会留下孤儿** —— 能力声明、文档、测试是否能跟着一起消失
3. **加第四个加载器有多难** —— 这是平台接缝设计的酸性测试
4. **升级 MC 版本时成本函数是什么** —— 如果成本随「所有文件」增长，架构就是错的

下面每一条设计都对着这四问回答。

---

## 1. 七条不变量

违反任何一条，架构就退化。这些不是「尽量」，是硬约束，review 时逐条对照。

| # | 不变量 | 违反的后果 |
|---|---|---|
| I1 | `net.minecraftforge.*` / `net.neoforged.*` / `net.fabricmc.*` 只允许出现在 `platform/<loader>/` 下 | 共享库失去「一份源码编进三家」，退化成三份维护 |
| I2 | `net.minecraft.client.*` 只允许出现在 `client/` 包 / `src/client/java` 源集 | 专用服务器上 `NoClassDefFoundError`；且错误出现在运行时而非编译期 |
| I3 | 能力（trait）由路由注册表**派生**，不允许手写 `capabilities` 字段 | 声明与实现漂移，且漂移是静默的 |
| I4 | 探针只**观察与驱动**，不做断言、不修被测 MOD | 探针变成测试框架，失去复用性；断言逻辑散落在 Java 与 JS 两侧 |
| I5 | 动作语义（`/action/*`）与协议机制（发包 / 注入输入）分离，后端可按端替换 | 「全操作」被写死在某一种实现里，服务端与客户端无法各自演进 |
| I6 | 一条路由 = 一个 trait 归属 + 一个端归属，二者都显式声明 | 无法回答「这个实例能做什么」，负向断言无从下手 |
| I7 | **一个功能的改动落点必须局部化**：只许改它自己的 Module 文件 + 注册表一行；禁止顺手重构别的 Module 或 `core` | 多分支回迁（§11.1）时每处越界改动都变成一次人工冲突解决；冲突积压到一定程度，回迁就会停，旧版本随之死掉 |

---

## 2. 五条边界

### B1 端边界（server ↔ client）

| | 硬边界 | 表现 |
|---|---|---|
| 不可达 | `net.minecraft.client.*` 在专用服务器上不存在 | 编译期不报错、运行期 `NoClassDefFoundError` |
| 隔离手段 | Fabric：`splitEnvironmentSourceSets()` 物理隔离；Forge / NeoForge：同一个 classpath，靠 `Dist` 判断 + JVM 懒加载 | — |
| 唯一实证 | 原探针同一 jar 在 Forge 1.20.1 上双端可跑 | 懒加载策略成立 |

**规则**：客户端专属代码进 `client/` 包；平台入口负责「在不该加载的时候不要碰它」。

### B2 加载器边界

```
platform/fabric/     ─┐
platform/forge/       ├─ 只有这三个目录知道加载器存在
platform/neoforge/   ─┘
shared/              ── 零加载器 import（I1）
```

**接缝（P1 实测修正）**：原设想是一个 `RegistryBridge` 接口（约 15 行）把 21 行 `ForgeRegistries.*` 收敛起来。**P1 证明这个接缝不需要** —— `BuiltInRegistries.BLOCK` / `.ITEM` 本身就是 `net.minecraft.*`，13 处替换是纯粹的机械改名，共享库一行加载器 import 都没引入。

⇒ 真正的平台接缝只剩**三样**（且都是「加载器 API 才能做到的事」，不是「原版 API 的包装」）：

| 接缝 | 为什么必须在 `platform/` | 量 |
|---|---|---|
| 入口（`@Mod` / `ModInitializer` / `DedicatedServerModInitializer`） | 加载器 API 本身 | 每家 40-60 行 |
| FakePlayer 构造 | Forge 的 `FakePlayerFactory` 已移除，Fabric 无对应物 | 每家 100-300 行（P4） |
| mixin 注册通道（**仅在需要 mixin 时**） | 三家通道不同（见 §9） | 约 10 行 |

**判据**：往 `platform/` 放东西之前先问一句「这段代码里有没有任何一个 `net.fabricmc` / `net.minecraftforge` / `net.neoforged` 的符号？」没有 ⇒ 它属于 `shared/`。

### B3 层边界（DSH 插件 ↔ HTTP ↔ 探针）

| 层 | 可以做 | 不可以做 |
|---|---|---|
| DSH 插件（JS） | 起停进程、编排、跑断言、读写项目文件 | import 任何 MC 类；直接操作世界 |
| HTTP | 传输、鉴权（token）、序列化 | 业务判断 |
| 探针（Java） | 观察状态、驱动动作、执行 `/command` | 读写项目文件；决定测试通过与否（I4） |

### B4 版本边界（多分支下升级为「API 基线」）

用户已定**模式 C：多分支并行**（§11.1）—— 一条版本分支 = 一个 MC 版本，新功能从 `main` 回迁到所有受支持分支。这改变了 B4 的职责：

| 原来（模式 A） | 现在（模式 C） |
|---|---|
| 版本边界只用于**估算下次升级的工作量** | 版本边界还决定**一份代码能编进几条分支** |
| 引用面计量一次就够 | 引用面 = cherry-pick 冲突面的近似值，要持续盯 |

**两条定量指标**（每次提交后统计，写进 CI 输出）：

1. **引用面**：引用 `net.minecraft.*` 的文件数 / 不同类名总数 / 每文件 import 分布（找「重灾区」）
2. **API 基线**：`shared/` 只用「**所有受支持版本都存在的 `net.minecraft.*` API**」。当前受支持版本 = {26.3}，基线 = 26.3 全集；若将来加入 26.1，基线立刻收缩到 26.1 ∩ 26.3。

**基线收缩是会真的发生的事**。用户说过「可能开发出来后看后续还需要往前支持」（m00718）—— 那就是一次基线收缩，代价 = 把 `shared/` 里用到「26.1 没有、26.3 有」的 API 全部改写。现在能做的便宜保险：**优先用稳定老 API，绕过 26.x 新加的花哨接口**；确实需要 26.3-only API 时，在代码注释里标 `// 26.3+` 并集中在一处，将来好找。

**回迁冲突面的经验公式**：一个功能在 `main` 上花 X 人日，回迁到 N-1 条旧分支 ≈ (N-1) × 0.2–0.5 X。乘数取决于 I7 被遵守得多好 —— 改动落点越局部，乘数越靠近 0.2。

### B5 职责边界：探针 vs 测试

```
探针  →  「现在是什么状态」「我让你做了什么」「结果是什么」
测试  →  「这个结果对不对」
```

举例：类别② MOD 缺客户端时连接被拒，**探针报告** `{"ok":false,"reason":"<服务端原文>"}`，**测试断言**「这个 reason 是否等于预期」。探针永远不判断「对不对」——否则同一份探针无法服务不同测试意图。

---

## 3. 部署单元与产物

| 产物 | 形态 | 数量 | 说明 |
|---|---|---|---|
| 探针 mod | 一个 jar | **3**（每家一个） | Fabric / Forge / NeoForge 各一份，内含共享库 + 该平台适配层 |
| DSH 插件 | JS 包 | **1** | 三家共用 |
| 构建骨架 | 三个 gradle 项目 | **3** | 各自独立，不是复合构建 |

**为什么不做统一 jar**：用户已定「各加载器客户端分开测试」，运行时不需要知道对方是谁（见前置文档 §4.3）。统一产物是纯负担。

**为什么不用复合构建**：前置文档 §4.2 已判 —— 一次性搭建 3-5 倍成本，唯一优势（一条命令出 3 产物）对 dev-only 工具用不上。

---

## 4. 目录结构

```
mc26.3-probe/
├─ shared/                                  ← 一份源码，三家编译
│  ├─ src/main/java/com/mcdev/probe/
│  │  ├─ core/           HTTP 服务器 / 路由表 / JSON / 错误信封 / tick 控制 / 日志镜像
│  │  ├─ module/         模块与能力模型（Trait / Module / RouteRegistry）
│  │  ├─ world/          方块 / 世界状态
│  │  ├─ inventory/      背包 / 物品 / 容器菜单
│  │  ├─ craft/          合成 / 配方 / 掉落表
│  │  ├─ action/         PlayerAction 语义层 + 后端接口
│  │  ├─ platform/       RegistryBridge 接口 + 平台能力声明
│  │  └─ server/         FakePlayer 的框架无关部分
│  └─ src/client/java/com/mcdev/probe/client/   ← I2：唯一允许 import client 类的地方
│     ├─ screen/         屏幕快照 / 控件点击 / hover
│     ├─ input/          输入注入（移动 / 视角 / 按键）
│     └─ capture/        截图
│
├─ platform/
│  ├─ fabric/            入口 + FakePlayer + RegistryBridgeImpl + mixins
│  ├─ forge/             同上（EventBus 7，改动最大）
│  └─ neoforge/          同上
│
├─ probe-plugin/         ← DSH 插件（JS）
│  ├─ src/detect/        读项目文件判定加载器
│  ├─ src/launcher/      Launcher 接口 + Msl / RunBat / GradleRun 三实现
│  ├─ src/probe/         HTTP 客户端（按 trait 分组的调用封装）
│  ├─ src/orchestrate/   跨端编排六步
│  ├─ src/tools/         14 个 mcdev_* 工具定义
│  └─ src/assert/        断言（I4：断言只在这里）
│
├─ docs/
│  ├─ mc26.3-multi-loader-probe-feasibility.md
│  ├─ architecture.md    ← 本文件
│  └─ http-api.md        路由契约（由 /meta/routes 生成，见 §7.3）
│
└─ tools/
   ├─ gen-api-doc.mjs     从运行中的探针拉 /meta/routes 生成文档
   └─ nms-surface.mjs     统计 net.minecraft.* 引用面（B4）
```

**目录即策略**：`shared/src/main` 与 `shared/src/client` 的划分不是审美，是 I2 的物理落地；`platform/*` 的划分是 I1 的物理落地。

---

## 5. 依赖规则（可 import 矩阵）

行可以 import 列：

| ↓ import → | core | module | world | inventory | craft | action | platform | client | 加载器 API |
|---|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| **core** | — | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |
| **module** | ✓ | — | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |
| **world / inventory / craft** | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ |
| **action** | ✓ | ✓ | ✓ | ✓ | ✗ | — | ✓ | ✗ | ✗ |
| **client/** | ✓ | ✓ | ✓ | ✓ | ✗ | ✓ | ✓ | — | ✗ |
| **platform/&lt;x&gt;** | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | 仅入口引用 | **✓ 仅此列** |

三条硬规则：

1. **`core` 不依赖任何业务模块**（含 `module`）。它只知道「有一个路由表」这个抽象，不知道路由里装了什么。
2. **`client/` 可以被 `world` / `inventory` / `action` 依赖吗？不行。** 方向是单向的：client 引用共享语义，共享语义不知道 client 存在。客户端能力通过 `action` 的后端接口反向注入。
3. **`platform/*` 是唯一能碰加载器 API 的层**，且它只做四件事（§9）。

---

## 6. 功能模块模型

这是「便于增删功能」的核心。三样东西：`Trait`、`Module`、`RouteRegistry`。

### 6.1 Trait（能力标签）

Trait 是**能力的名字**，不是代码路径。一组固定字符串，跨端跨加载器一致。

```java
enum Trait {
    CORE,          // /status /log/tail /meta/routes
    COMMAND,       // /command
    WORLD,         // 方块读写、世界状态
    INVENTORY,     // 背包、物品、容器菜单
    CRAFT,         // 合成、配方、掉落
    PLAYER_ACTION, // /action/*
    FAKE_PLAYER,   // 假玩家生命周期
    CLIENT_UI,     // 屏幕快照、控件点击、hover、截图
    INPUT,         // 移动、视角、按键注入
    NET,           // /connect /quit
    GAMETEST       // GameTest 触发与结果读取
}
```

**Trait 是能力的唯一真相**：`/status` 的 `traits` 字段由注册表派生（I3），不手写。客户端有 `CLIENT_UI` 而服务端没有，这不是 if/else，是注册表里根本没有那些路由。

### 6.2 Module（功能模块）

一个功能 = 一个 Module。加功能 = 加一个类 + 在模块表里加一行；删功能 = 删那一行（+ 删类）。

```java
interface Module {
    String id();                    // "world"
    Set<Trait> traits();            // {Trait.WORLD}
    Side side();                    // BOTH / SERVER / CLIENT
    void register(Registrar r);     // 注册路由与命令
}
```

启动流程：

```
Modules.all()
  → filter(side 匹配当前运行时)
  → filter(platform 支持)
  → 逐个 register()
  → 注册表得出 traits 全集
  → /status 直接返回该全集
```

**为什么值得这层抽象**：现在 32 条路由、11 个能力域。如果没有模块表，「服务端有哪些能力」这句话就要靠读 1,902 行代码回答；有了模块表，`/status` 一句话回答，且**删功能时能力声明自动跟着消失**（不会留下孤儿声明）。

[估] 基础设施成本：`Trait` ~30 行、`Module` ~40 行、`RouteRegistry` ~120 行、`Modules` 表 ~30 行 ≈ **220 行**。

**迁移成本极低**：原代码注册是集中的（`ProbeServer.java:63-77` 15 条、`ClientProbeServer.java:69-85` 17 条），处理器是独立的静态方法。改造只动这 32 行注册语句，**处理器内部一行不动**。

### 6.3 RouteRegistry（路由表）

```java
routes.add(Route.get("/world/block")
    .trait(Trait.WORLD)
    .side(Side.SERVER)
    .doc("读一个方块：返回 blockId / state / 光照")
    .handler(RouteRegistry::handleWorldBlock));
```

注册表派生三样东西：

1. `/status` 的 `traits`
2. `/meta/routes` 的路由清单（→ 生成 `docs/http-api.md`）
3. **`TRAIT_UNAVAILABLE` 的响应内容** —— 访问本实例没有的能力时，返回缺失的是哪个 trait、当前是哪一端、这个 trait 在哪一端可用

第 3 条直接替代原代码的 `handleNotClient`（`ProbeServer.java:75-77` 三条路由都指向它，`ProbeServer.java:573` 返回 501 文案）。新做法不是「三条路由指向同一个拒绝函数」，而是**那三条路由在服务端根本不注册**，由路由表统一拦截并给出结构化理由。少一处特例，多一处一致性。

### 6.4 命令注册表

`/mcdev` 命令与路由同属能力，同样由 Module 注册。原 `ProbeCommands.java`（107 行，6 个子命令）拆进对应 Module：

| 子命令 | 归属 Module |
|---|---|
| `status` | CORE |
| `freeze` / `step` | CORE（tick 控制） |
| `fakeplayer spawn\|use\|attack` | FAKE_PLAYER |

这样删掉假玩家功能时，命令也跟着消失，不会留下「命令存在但路由不存在」的中间态。

---

## 7. HTTP 契约

### 7.1 统一响应信封

**成功**：

```json
{"ok": true, "data": { ... }}
```

**失败**：

```json
{"ok": false, "error": {
  "code": "TRAIT_UNAVAILABLE",
  "message": "trait 'client-ui' is not available on side=server",
  "trait": "client-ui",
  "side": "server",
  "detail": { }
}}
```

错误码固定集合（不允许随手加）：

| code | HTTP | 含义 |
|---|---:|---|
| `BAD_REQUEST` | 400 | 参数缺失 / 类型错 |
| `GAMEMODE_MISMATCH` | 400 | 动作要求的模式与玩家当前模式不符（I5 前置校验） |
| `PLAYER_NOT_FOUND` | 404 | 玩家 / 假玩家不存在 |
| `TARGET_NOT_FOUND` | 404 | 目标方块 / 实体 / 槽位不存在 |
| `TRAIT_UNAVAILABLE` | 501 | 本实例没有这个能力 |
| `FROZEN` | 409 | 世界被冻结，动作已排队或拒绝 |
| `TIMEOUT` | 504 | 等待 tick / 条件超时 |
| `INTERNAL` | 500 | 未分类异常，`detail` 带栈 |

**为什么统一**：现在的错误处理是各处理器自己 `error(ex, 501, "...")`，客户端只能靠字符串匹配。统一信封之后 DSH 插件可以按 `code` 分支，而不是按文案分支 —— 文案随时会改，code 不会。

### 7.2 `/status` 契约

```json
{
  "ok": true,
  "data": {
    "api": 1,
    "probe": "0.1.0",
    "side": "server",
    "loader": "forge",
    "mcVersion": "26.3",
    "traits": ["core", "command", "world", "inventory", "craft", "fake-player"],
    "actions": { "move": "full", "mine": "full", "spectator": "none" },
    "tick": 12345, "gameTime": 12345, "dayTime": 6000,
    "players": [...], "maxPlayers": 20, "level": "world",
    "frozen": false, "stepBudget": 0
  }
}
```

新增三个字段，全部是「便于增删」的支撑：

- `api` —— 契约版本。DSH 插件启动时校验，不匹配就明确报错而不是行为诡异。
- `traits` —— 派生而得（I3）。
- `actions` —— 动作粒度的覆盖度（full / partial / none），因为 trait 是粗粒度，「有 PLAYER_ACTION 但旁观动作不支持」这种情况必须能表达。

### 7.3 `/meta/routes`（自描述）

返回注册表全量：路径、trait、端、文档、参数 schema 摘要。

用途：

1. `tools/gen-api-doc.mjs` 拉一次，生成 `docs/http-api.md` —— **文档不会漂移**，因为它不是手写的
2. DSH 插件启动时可以 diff「插件期望的路由」vs「探针实际的路由」，版本错配立刻暴露

### 7.4 路由重构：`/ui/*` 拆分（已采纳，m00625）

现状：`/ui/*` 在两端都存在但**语义不同**。

| 端 | `/ui/snapshot` `/ui/click` 的真实含义 |
|---|---|
| 客户端 | 屏幕（`Screen`）：控件快照、坐标点击 |
| 服务端 | 不存在 —— 三条路由都指向 `handleNotClient`，返回 501 |

而服务端真正需要的是**容器菜单**（`AbstractContainerMenu`）的槽位读写，对应 §10.5 的 29 个 `*Menu`。现在这块**完全缺失**。

建议拆成两个命名空间：

| 路由 | trait | 端 | 语义 |
|---|---|---|---|
| `/container/snapshot` | INVENTORY | BOTH | 打开中的 `AbstractContainerMenu` 的槽位列表 |
| `/container/click` | INVENTORY | BOTH | 点击槽位（服务端走 `menu.clicked`，客户端走 `ClickSlotCfg` 发包） |
| `/ui/snapshot` | CLIENT_UI | CLIENT | `Screen` 控件树 |
| `/ui/click` `/ui/hover` | CLIENT_UI | CLIENT | 屏幕控件交互 |
| `/screenshot` | CLIENT_UI | CLIENT | 截图 |

**理由**：「容器槽位」与「屏幕控件」是两种完全不同的东西，同名会长期误导调用方，也会让服务端的容器能力永远缺一块。**成本**：路由改名，DSH 插件的调用处要跟着改 —— 但插件本来就要移植，此时改零额外成本。

---

## 8. 动作层（`PlayerAction`）

> **实现状态（2026-10-20）**：两个后端都已落地。客户端 `ClientActionBackend` 12 条端点全部真机跑通；
> 服务端 `ServerActionBackend` 做九个「天然权威」动作（`teleport/gamemode/ability/mine/place/use/attack/pick/inventory`），
> 与客户端**同名同信封**、用 `data.backend` 区分，`/status` 的 `actionBackend` + `actions` 是选端依据。
> 「纯服务端跑动作」已验证**不需要 FakePlayer** —— 对在线玩家直接调 `ServerPlayerGameMode` / `Player.attack`；
> `inventory` 的服务端 op 扩到 10 个（补 `drop/close/button/trade/rename`）。
> 两个回归套件都在仓库里可重跑：`tmp/p4a-action-suite.ps1` **19/19**、`tmp/p4a-server-suite.ps1` **20/20** ——
> 证据与偏差见 [p4a-action-layer-record.md](p4a-action-layer-record.md) §3 / §9 / §12 / §13（与本节设计的差异：
> `inventory` 的 op 集合以记录为准、`move` 的远端瞬移拆成「客户端位移（受世界 gamerule
> `player_movement_check` 把关）」与「`server-teleport`（服务端探针 `/command tp` 代劳）」两条路、
> 包覆盖率 21/58 而非 45/58；服务端后端不覆盖 serverbound 包，它发的是 clientbound 同步包）。
> **第二批（聊天 / 告示牌 / 命令方块）已落地**：新增 capability `block_extra` 与六条路由
> （客户端 `/chat/send`、`/block/sign/edit`、`/block/sign`、`/block/command-block`；服务端 `/block/sign`、`/block/command-block`），
> 累计覆盖 24/58；`/chat/suggest` 故意不开（异步推送与同步端点契约不合）。记录 §15。
> `server-teleport` 是跨端编排的第一条真链路（记录 §11）。
>
> **通用能力补丁（覆盖审计之后）**，四条都只加通用面、不为任何被测 mod 特化：
> ① 客户端 `GET /chat/tail?n=30`（capability `chat_read`）读客户端**当前**看到的聊天与 actionbar
> （`actionbar` / `actionbarSource` / `lines` / `text`），用来断言「玩家屏幕上真的出现了」——
> 这是服务端 `/log/tail` 证明不了的那一半；
> ② 服务端 `/world/block|break|mine`、`/items`、`/block/sign`、`/block/command-block` 统一接受 `dim`
> 参数并走 `requireLevel(dim)`，未知维度回 **400 + 已载维度清单**（旧行为是静默回落主世界）；
> ③ 场景 DSL 步骤级 `retry: {times, intervalMs}`（通过即早退，`results.json` 记 `attempts`）；
> ④ 断言加 `expect.contains`（字段包含子串）与 `expect.text`（响应体包含子串）。
> 自检场景 `scenarios/capabilities.json`（12 条，Fabric 实测 12/12）。
>
> **独立客户端（2026-10-20）**：`client.mode` 四态 —— `gradle`（本仓 `runClient`）/
> `standalone`（`scripts/lib/Launch.ps1` 读 `versions\<版本>\<版本>.json` 拼命令行，**不下载**）/
> `external`（用户自开，只等 39002）/ `none`。探针配置从 `<gamedir>/config/mcdev-probe.properties`
> 读（`-D` 覆盖），因为真实启动器不便注入 JVM 参数；`install-probe.ps1` 写 manifest + sha256，
> `remove-probe.ps1` 只删自己装的东西。实例加 `side: server|client|both`，对应 devrun 起哪几端；
> 场景里出现对端不存在的步骤时在**加载阶段**拒绝（不是跑到一半才发现）。

这是「玩家操作全集」那 1,000-1,500 行的落点。设计核心是 **一个语义 API + 两个后端**（I5）。

### 8.1 语义 API（`/action/*`）

```json
POST /action/move      {player, to:{x,y,z}, look:{yaw,pitch}, mode:"teleport"|"walk"|"server-teleport"}
POST /action/mine      {player, pos, face}
POST /action/place     {player, pos, face, item}
POST /action/use       {player, target:"block"|"entity"|"item", pos?, entityId?, hand?}
POST /action/attack    {player, entityId}
POST /action/pick      {player, pos}             // 中键 pick block
POST /action/inventory {player, op:"give"|"clear"|"set-selected"|"click"|"slots"|"drop"|"close"|"button"|"trade"|"rename", ...}
POST /action/gamemode  {player, mode:"survival"|"creative"|"adventure"|"spectator"}
POST /action/ability   {player, flying?:bool, invulnerable?:bool}
POST /action/spectate  {player, entityId}
POST /action/respawn   {player}
POST /action/sleep     {player, bedPos}
```

**每个动作显式声明前置**：

- 需要的 `GameType`（`mine` 在 spectator 下返回 `GAMEMODE_MISMATCH`，而不是静默无效）
- 需要的 trait
- 在两种后端下的覆盖度

三段式校验在入口完成：`trait 可用? → gamemode 符合? → 目标存在?`。任一不满足就返回结构化错误。**这是把「静默失败」变成「响亮失败」的地方**，也是原代码最大的缺陷（`/command` 造结果，看不出动作路径有没有真的走通）。

### 8.2 两个后端

```
interface ActionBackend {
    Set<Trait> supports();
    Coverage coverage(Action a);
    Result execute(Action a);
}
```

| 后端 | 谁在用 | 覆盖 | 实现手段 |
|---|---|---|---|
| `ClientActionBackend` | 客户端探针 | **接近全量**（12 端点） | 注入 `Input` / `KeyMapping`、构造 serverbound 包、发 `ClientPacketListener` |
| `ServerActionBackend` | 服务端探针 | **九个天然权威动作**（9 端点 + `inventory` 的 10 个 op） | `ServerPlayer.teleportTo` / `setGameMode` / `Abilities`+`onUpdateAbilities` / `ServerPlayerGameMode.destroyBlock` / `useItemOn` / `Player.attack` / `getCloneItemStack` / `AbstractContainerMenu.clicked`；**FakePlayer 属可选增强** |

启动时按端选后端，`/status` 的 `actionBackend`（`client` / `server`）与 `actions` 字段报告本端支持的动作。
测试不需要知道后端是谁 —— 两端是同名路由、同一种信封，只有 `data.backend` 不同。

**「纯服务端跑动作」已验证不需要 FakePlayer**（2026-10-20）：对**在线玩家**直接调
`ServerPlayerGameMode` / `Player.attack` 即可覆盖 `mine/place/use/attack/pick`，
`inventory` 再补 `drop/close/button/trade/rename`（同一个 handler 语义）。
FakePlayer 只剩「无客户端 CI」和「多玩家并发」两个场景才需要。

**用户决定（m00625 原话要点）**：*"本身测试就是在服务器端加载 MOD 然后开客户端进行探测，角色操作什么的也都在客户端实现"*。

这条决定比「最小集」更强 —— 它把服务端探针的定位钉死成**观察者 + 环境控制**：

| 服务端探针的职责 | 内容 |
|---|---|
| 观察（必需） | `/status`、`/world/block`、`/item`、`/items`、`/log/tail`、`/container/*` |
| 环境控制（必需） | `/command`、`tick` 冻结与步进 |
| 角色驱动（**可选**） | FakePlayer —— 只在「多玩家并发」与「无客户端的纯服务端自动化（如 CI 跑 GameTest）」两个场景需要 |

**⇒ FakePlayer 不进 P0，降级为可选增强功能。** 连带影响：

- 三个平台各 100–300 行的假玩家实现**可延后**（`ServerActionBackend` 先只做观察 + `/command`）
- Forge 26.3 自建假玩家的可行性**不再是阻塞项**（原前置文档 §14 风险 #2 从「边界改变」降为「功能缺失」）
- 规模估算相应下调（见 §14）

**这条设计对应前置文档 §10.3 的结论②**：能力面最大的一层放客户端，避免在三个加载器上各写一套 300 行假玩家。

### 8.3 服务端容器能力（新补的缺口）

`ServerActionBackend` 必须能操作 `AbstractContainerMenu` —— 这是服务端唯一能做「背包/交易/合成台」这类交互的途径（没有 GUI，只能直接 `menu.clicked(slot, button, type, player)`）。

覆盖 §10.5 里 14 个容器包的一个子集，但**一次实现覆盖 29 个 `*Menu`** —— 因为它们都继承同一个 `AbstractContainerMenu`。这是最高性价比的一块。

### 8.4 实现状态（2026-10-20，与本节设计的差异）

落到代码上的东西（证据见 [p4a-action-layer-record.md](p4a-action-layer-record.md)）：

| 本节设计 | 实际实现 | 差异与理由 |
|---|---|---|
| `ActionBackend { supports(); coverage(Action); Result execute(Action); }` | `ActionBackend { name(); actions(); JsonObject execute(ActionRequest) throws ActionException }` | `Set<String>` 代替 Trait 枚举（P0 的 trait 体系还没接进 HTTP 层）；`coverage` 降级为每条响应里的 `packets` 字段；没有 `Result` 类型 —— 成功返回 `data`，失败抛 `ActionException(code, detail)` |
| 三段式校验在入口 | 仍在入口，但**校验与实现写同一个方法**（`requireMode(...)` 在后端内部） | 防止两处漂移：入口只知道「这是哪个 action」，不知道它的 gamemode 要求 |
| 两个后端按端选 | 两端都已落地：`/status.actionBackend` 为 `"client"` 或 `"server"`，`actions` 列本端支持的动作；路由同名、信封同形，只有 `data.backend` 不同 | 服务端做九个「天然权威」动作（`teleport/gamemode/ability/mine/place/use/attack/pick/inventory`）——对在线玩家直接调 `ServerPlayerGameMode` / `Player.attack`，所以「纯服务端跑动作」**不需要 FakePlayer**；客户端仍是操作的主力（用户决定 m00625），这样不必在三个加载器上各写一套假玩家 |
| `/action/move {mode:"teleport"}` | 远端瞬移由**世界 gamerule `player_movement_check`（默认 true）**把关：关掉后 60 格实测一次到位（`horizontalError:0.0`）。另有一条独立规则：服务端每 tick 只接受一个带位置的位置包，手搓第二个即 `multiplayer.disconnect.invalid_player_movement` 断线，故端点只改本地位置、位置包交给 `LocalPlayer.tick()`。回报拆成 `horizontalError` / `verticalDrift` / `snappedBack` / `hint`（记录 §10） | 契约里 `"teleport"` 的语义要写成「客户端位置变更 + 服务端是否接受」，不能承诺必然瞬移；端点不替调用方改世界设置 |
| 「跨端」不在动作层 | 新增第三种模式 **`mode:"server-teleport"`**：客户端探针调服务端探针 `/command tp` 代劳，再 `/player/state` 读回权威坐标；基址 `-Dmcdev.probe.server.url`（默认 `http://127.0.0.1:39001`），不可达回 501 `TRAIT_UNAVAILABLE`。踩过两个坑：`http.proxyHost` 会把回环请求送进代理、JDK `HttpClient` 的 h2c 升级会让 `com.sun.net.httpserver` 一直等到超时（记录 §11.3） | 纯客户端做不到「远端到位」，而跨端编排是 P4 之后的主线；这条模式是它的最小可用切片，只用一个动作验证整条链路（起服→连端→代劳→读回），其余动作不依赖它 |
| `ServerActionBackend` 用 `menu.clicked(...)` 覆盖 29 个 `*Menu` | 已实现：`inventory` 的 `click` 走 `menu.clicked(slot, button, ContainerInput, player)` + `broadcastChanges()`；另有 `give/clear/set-selected/slots` 与「GUI 不可达但服务端能代跑」的 `drop/close/button/trade/rename`，共 10 个 op | 服务端没有 GUI，「容器交互」的唯一落点就是这个方法；`slots` 读 `InventoryMenu` 46 槽给调用方一份权威快照；`trade`/`rename` 分别落 `MerchantMenu.setSelectionHint` / `AnvilMenu.setItemName`，没开对应界面时**结构化报错**而不是假装成功 |
| `GameType` 的来源 | 新增严格解析 `GameTypes.parse(String)`：显式 switch 四选一，未知输入 400 | **不能用 `GameType.byName(raw)`** —— 单参重载是 `byName(raw, SURVIVAL)`，未知输入静默返回 SURVIVAL（javap 实测），会让 `{"mode":"nonsense"}` 变成「把玩家切成生存并回 200」（记录 §12.3） |
| 包覆盖 ≥45 / 58（feasibility §10.5） | **21 / 58** | 12 个语义端点摊不出 45 个包；差的 37 个在特殊方块 UI / 查询管理 / 聊天 / 容器剩余 / 骑乘，需新增端点 |

其余设计原样成立：8 个错误码、结构化 `detail`、长动作在 HTTP 线程 `sleep` 而每步只 `onClient` 一次。

---

## 9. 平台适配面：每家只许改四样

```
platform/<loader>/
  1. 入口类            40-60 行  @Mod / ModInitializer / DedicatedServerModInitializer
                              + 客户端入口（Fabric: ClientModInitializer / NeoForge: ClientStartedEvent /
                                Forge: 首个 ClientTickEvent.Post，EventBus 7 已无 @Mod.EventBusSubscriber）
  2. FakePlayer 实现   100-300 行 服务端假玩家（三家机制不同）—— 可选，P4
  3. ~~RegistryBridgeImpl~~  **P1 实测：不需要**（BuiltInRegistries 属于 net.minecraft.*）
  4. mixin 配置        ~10 行  仅在确实需要 mixin 时
```

客户端入口在 Forge / NeoForge 上只能与主代码同 jar（两家构建只有一个 `main` source set），
端边界靠 `@Mod` 构造器里的 `dist == CLIENT` 守卫 —— 实测三台专用服务器加载含客户端类的新 jar 后
**不加载**客户端入口、不监听 39002（见 [p4a-action-layer-record.md](p4a-action-layer-record.md) §14.3）。
三家客户端入口**都已在真机上跑通**（2026-10-19 21:20）：同一份 19 条套件在 Fabric / Forge / NeoForge
的 `runClient` 上各跑一遍，均 `PASS=19 FAIL=0`（记录 §14.7）；测试姿态统一为「默认创造、按需临时切模式」
（记录 §14.8），端边界仍是编译期（Fabric）或 `dist` 守卫（另两家）。

第 4 项的**注册通道**三家不同，P0 V7 实测（feasibility §13.1）：

| | 怎么让加载器知道有这个 mixin config |
|---|---|
| Fabric | `fabric.mod.json` 的 `mixins` 字段 |
| **Forge** | **jar `MANIFEST.MF` 的 `MixinConfigs`** —— `mods.toml` 里的 `[[mixins]]` 在 26.3 已不读，写进去是**静默失败**（不报错、不加载、无提示） |
| NeoForge | `neoforge.mods.toml` 的 `[[mixins]]` |

`compatibilityLevel` 写 `JAVA_25` 只为消警：Forge 解析到的 `org.spongepowered:mixin:0.8.7` 枚举最高 `JAVA_21`，但实测注入 major=69 的类仍然成功。

**另：mixin 在本项目里不是必需品。** P0 V7 顺带证明 tick 冻结有公开 API（`MinecraftServer.tickRateManager()`），原探针 `ServerLevelMixin` 那一套（`@Inject` 到 `ServerLevel.tick` + `remap=false`）可以整段删掉。mixin 只在「必须改原版行为且无 API 可达」时才是最后手段 —— 新增 mixin 前先证明 API 做不到。


**其他任何改动都是 bug。** 如果某天发现「Forge 需要额外改一个共享类」，那说明接缝位置错了，要改接缝而不是加特例。

各家的已知差异（前置文档 §5 的浓缩）：

| | Fabric | Forge | NeoForge |
|---|---|---|---|
| 入口 | 三个并列接口（main / client / server） | 构造注入 `FMLJavaModLoadingContext` | 构造注入 `(IEventBus, ModContainer)` |
| 事件总线 | `ServerLifecycleEvents` | **EventBus 7**（`BusGroup`） | **8.0.5**（`IEventBus`） |
| FakePlayer | 自建（借 Carpet 382 行 → 裁到 ~100） | **自建**（`FakePlayerFactory` 已移除） | **现成**（`FakePlayerFactory`） |
| 类别表达 | `environment` 字段 | `mods.toml` `displayTest` | `PayloadRegistrar.optional()` |
| GameTest | `runGametest` | `runGameTestServer` | `runGameTestServer` |

### 9.1 酸性测试：加第四个加载器

如果架构正确，加一个加载器应该是：

1. 复制一份最接近的 `platform/<existing>/` → `platform/<new>/`
2. 改那四样
3. 加一个 gradle 项目（官方 MDK + 一行共享 srcDir）
4. DSH 插件加一个 `detect` 分支 + 一个 `Launcher` 实现

**预算：≤400 行 + 1 个 gradle 项目 + 半天。** 超出这个预算说明架构漏了，要修架构而不是硬加。

---

## 10. DSH 插件层（JS）

### 10.1 五个子模块

```
src/detect/      加载器判定（读 fabric.mod.json / mods.toml / neoforge.mods.toml / build.gradle）
src/launcher/    Launcher 接口 → MslLauncher / RunBatLauncher / GradleRunLauncher
src/probe/       HTTP 客户端，按 trait 分组（ProbeClient.world() / .inventory() / .action()）
src/orchestrate/ 跨端编排六步
src/assert/      断言（I4：断言只在这里）
src/tools/       14 个 mcdev_* 工具（薄封装，不做业务）
```

### 10.2 `Launcher` 接口为什么必要

前置文档 §9.5 指出：MSL 的「一个 Core jar + JVM 参数」模型对 Fabric 成立，对 Forge / NeoForge 可能不成立（它们是 `libraries/` + `@win_args.txt`）。**这条不确定性不该传染到编排层。**

```js
interface Launcher {
  async start(instance) -> {pid, probePort, ready}
  async stop(pid)
  async status() -> {running, phase}
}
```

三个实现：`MslLauncher`（Fabric 首选）、`RunBatLauncher`（Forge/NeoForge 兜底）、`GradleRunLauncher`（开发迭代最快）。P0 的 V8 验证结果只影响「默认选哪个」，不影响编排层代码。

### 10.3 编排六步（不变量：每一步都可中断与复原）

1. 起服务端 → 轮询 `/status` 直到 `ok` 且有 `core` trait
2. 起客户端 → 同上
3. `/connect`（含负向断言分支：预期失败时校验 `reason`）
4. 轮询 `/status.players` 达到预期
5. 驱动动作（`/command`、`/world/*`、`/container/*`、`/action/*`、`/ui/*`）
6. 收尾：拉 `/log/tail`、关客户端、关服务端

**每步都要有超时 + 明确的失败码**。步骤之间不留隐式状态 —— 编排层不缓存「上一个动作成功了」这种信息，每次从 `/status` 读。

### 10.4 第一版编排的落地形态（2026-10-20，记录 §16）

DSH 插件层（§10.1 的六个 JS 子模块）尚未开工；先把**同一套六步**用 PowerShell 落成可执行脚本，
用它把「步骤次序、等待条件、清理动作」这些隐性知识固化下来，插件层再包这层：

| 六步 | `scripts/devrun.ps1` 的对应实现 |
|---|---|
| 1 起服务端 | `Start-Process java` + 轮询 `GET /status`（240s）；**`-Dmcdev.probe.port` 必须排在 `-jar` / `@args` 之前** |
| 2 起客户端 | `Start-Process pwsh` 跑 `gradlew runClient` + 轮询客户端 `/status` |
| 3 `/connect` | `POST /connect?host&port`（**只认 query**，JSON body 会被忽略） |
| 4 等玩家 | 轮询 `/status.player` 非空 → 读名字 → `POST /command {"cmd":"op <name>"}` |
| 5 驱动动作 | 场景文件 `scenarios/*.json`：`steps[{id,target,method,path,body,expect{status,json 点路径}}]` |
| 6 收尾取证 | `summary.md`（结论 + 阶段耗时 + 用例表 + 两侧 `/status` 原文 + 日志尾巴）；`finally` 里 `/quit` + `/command stop` |

两条设计取舍值得沿用到插件层：

- **`expect` 同时断言 HTTP 状态码与 JSON 点路径**，并且**故意把响亮失败写成用例**（400/404 也是契约）；
  只测成功路径的套件会把「静默 200」当成通过。
- **收尾的端口等待只告警不抛错**：不能因为「端口还没释放」把一个已经跑成功的场景改判为失败。

---

## 11. 版本演进

### 11.1 版本策略：单分支起步，多版本只预留（m00831 定案）

**用户决定（m00831 原话要点）**：「每个 26.X 算一个大版本（后续明年就是 27.X、后年是 28.X），即所有 26.1.X 只追最后一个，其他类推」；「目前开发暂时先支持 26.3，别的等发布后看反响如何再说，不过先预留对其他版本的支持的可能性」。

**版本计数口径（已定）**

| 口径 | 例子 | 是否单独一条分支 |
|---|---|---|
| **minor（采用）** | 26.1 / 26.2 / 26.3 | 是一条；该 minor 下所有补丁版归到最后一个 |
| patch（不采用） | 26.1.1 / 26.1.2 | 不单独分支，只跟 minor 的最后一个 |

Mojang 官方 manifest（`piston-meta.mojang.com/mc/game/version_manifest_v2.json`）中 26.x 的 `type=release` 共 **5 个 id**：`26.1`（2026-03-24）/ `26.1.1`（04-01）/ `26.1.2`（04-09）/ `26.2`（06-16）/ `26.3`（09-15）。按 minor 口径 ⇒ **26.x 线最多 3 条分支**，明年 27.x 线重新从 1 计数。

**支持范围（暂定，m00898 定案）**

| 项 | 值 |
|---|---|
| 下限 | **26.1.2**（26.1.x 线只追最后一个补丁版，不追 26.1.0 / 26.1.1） |
| 上限 | 26.3（**当前唯一实现**） |
| 26.x 线内分支预算 | 最多 3 条：`mc26.1` / `mc26.2` / `main`(=26.3) |
| 明确不做 | 1.21.11 及更早 —— 硬边界，见 §11.4 |

「先做 26.3」⇒ 现在只有 `main`；26.1 / 26.2 只是**已声明的范围**，不是欠下的工作量，也不进任何里程碑。范围声明的唯一作用是给 §11.2 R2 一个明确的 `compat/` 目标基线，避免将来补做时才发现基线下限没想清楚。

> **易混淆点**：「支持范围 26.1.2~26.3」是**项目级声明**（哪些版本会有自己的构建），**不是单个 jar 的兼容范围**。26.3 的探针 jar 仍然只声明 `~26.3`（§11.3 N5），跨版本加载不会因此变成可用。

**现在的状态（2026-10）**

- 只有 `main`，且 `main` == 26.3。**不切任何保护分支** —— 切了就要养，养 = 3 加载器 × N 版本的测试矩阵。
- `latest.release = 26.3`，`latest.snapshot = 26.4-snapshot-4` 已在跑 ⇒ **26.4 预计 2026-12 前后**。到那天才决定是否切第一条 `mc26.3`。
- 也就是说「预留」至少有一个季度的观察期，正好是发布后看反响的窗口。

**多分支拓扑（已设计，未启用）**

```
main        ← 现在 == 26.3。26.4 发布时前进到 26.4
  └ mc26.3  ★ 未启用。26.4 发布且决定继续支持 26.3 时才从 main 切出
  └ mc26.2  ★ 未启用。往前补做时才存在
  └ mc26.1  ★ 未启用。同上
```

- **新功能**：一律在 `main` 上开发、测试、合并。
- **回迁**：功能稳定后 cherry-pick 到每条受支持分支，各自跑一遍该版本的测试矩阵（3 加载器 × 该版本）。

**回迁判定标准（预案；现在没有可回迁的目标分支）**

| 改动类型 | 回迁 | 理由 |
|---|---|---|
| bug 修复 | **必须** | 旧版本用户同样会踩 |
| 影响测试正确性的修复 | **必须** | 否则旧版本的测试结论不可信 |
| 新功能 | **按需** | 取决于你还在不在旧版本上开发 MOD |
| 依赖新版 API 的功能 | **不回迁** | 旧分支没有那个 API |
| 文档 / 发布流程 | 按需 | 版本专属文档跟着分支走 |

**commit 标记约定（现在就开始用，零成本）**

- `[backport: mc26.3]` —— 需要回迁，目标分支列出
- `[version-only: 26.4+]` —— 只在最新版本有意义，永不回迁
- 无标记 = 默认不回迁（保守优先）

**分支数量上限**

建议活跃分支 **≤3 条**。上限由**验证成本**决定而不是编辑成本：N 条分支 = 3N 台实例要跑一遍才算「支持」。编辑成本能靠 cherry-pick 摊薄，验证成本摊不薄。弃旧判据用 Modrinth facet 计数 —— 某版本活跃 MOD 数低于最新版的 10% 时就没有保留价值。

### 11.2 六条零成本预留纪律

「预留」的唯一可接受成本是零。下面六条是写代码时顺手遵守的纪律，不是额外工程；它们同时保证将来**多分支分叉**与**单源多版本（stonecutter 类）**两种走法都还走得通。

| # | 纪律 | 现在成本 | 将来省什么 |
|---|---|---|---|
| R1 | 版本号单一来源（`platform/*/gradle.properties` 的 `minecraft_version`），不散落到代码常量 | 0 | 加一个版本只改一行 |
| R2 | 版本敏感代码集中到 `compat/`；用到「低于目标基线」的 API 时标 `// @since 26.2+` / `// @since 26.3` | 0 | 转 preprocessor 时条件只落一个包，不用满仓找；收缩基线时有据可查 |
| R3 | 分支拓扑只留 `main`，不开 `mc26.3` 保护分支 | 0 | 26.4 发布时才切第一条，现在不养空分支 |
| R4 | 注册表一律 **append 不 insert**：`Route.get(...)` 行、`Trait` 枚举值、模块列表 | 0 | cherry-pick 不冲突 |
| R5 | 不用反射、不把 `net.minecraft.*` 类名写成字符串 | 0 | 版本间改名会**编译报错**（响亮），而不是运行时静默失败 |
| R6 | 一个功能一个 commit，message 带 `[feature: xxx]` | 0（纪律） | 回迁时能单独挑，不用拆大 commit |

R2 / R4 是 I7（改动落点局部化）在版本维度的延伸；R5 与 §2 B4（API 基线）配套。

**目标基线 vs 当前基线（R2 的操作化）**

| 概念 | 值 | 用途 |
|---|---|---|
| **当前基线** | 26.3 全 API | 编译器实际约束。现在写代码**只看这个**，不受无谓限制 |
| **目标基线** | 26.1.2 ∩ 26.3（暂定，m00898） | 只用于**打标记**，不约束编译 |

现在不收缩基线（否则白白放弃 26.2/26.3 的 API），只做一件事：**用到 26.1.2 没有的 API 时打 `@since` 注释**。将来真要补 26.1 时，`grep "@since 26\.[23]"` 就是全部改动点的上界 —— 这是把「往前支持」从「翻遍 5,000 行」降成「看一份清单」的唯一零成本手段。

标记口径：`// @since 26.2+`（26.2 起才有）、`// @since 26.3`（26.3 独有）。26.1.2 就有的 API 不标。

### 11.3 现在明确不做（避免过早工程化）

| # | 不做 | 理由 |
|---|---|---|
| N1 | stonecutter / preprocessor 单源多版本 | P0 还没验过构建链，再叠一层预处理器 = 双重风险，出事分不清是谁的锅 |
| N2 | 复合构建（root + N subproject） | 一次性搭建 3–5 倍，N=1 时纯亏 |
| N3 | `nms/` 全量封装层（把所有 MC 调用包一层） | 5,000 行全过 shim，样板爆炸，换来的是可读性更差而非更可移植 |
| N4 | 多版本测试矩阵（N×3 台实例） | 现在只有 1 个版本可测，建了也跑不起来 |
| N5 | 放宽 mod 元数据的版本约束（`"minecraft": ">=26.1"`） | **反直觉但必须写死精确版本**：跨版本加载只会更晚崩、报错更难懂。预留不等于放行 |

N1–N3 的触发条件是「受支持 minor 版本数达到 2」，不是日期。

### 11.4 硬墙：混淆边界在 26.1（m00831 的判断已验证）

用户判断「往前支持最大差异是混淆表在哪个版本没有」。**这个判断正确**，而且它是整条「往前支持」路线上唯一的硬边界 —— 比 API 面差异重要得多。

一手证据：

| 证据 | 内容 |
|---|---|
| Minecraft Wiki `Java Edition 26.1` | "the first Java Edition release version … to be fully unobfuscated without an accompanying obfuscated variant"（26.1 发布于 2026-03-24，最低 Java SE 25） |
| Fabric 官方博客 2025-10-31 | "Intermediary will no longer exist; the game will use Mojang's names at runtime"；去混淆自 1.21.11 之后的首个快照起 |
| 本机 `libraries/net/fabricmc/intermediary/` | `intermediary-0.0.0.jar` = **572 B**，`mappings/mappings.tiny` 仅 **25 字节**（`v1\tofficial\tintermediary\n`，只有表头）；`intermediary-1.21.11.jar` = **797,685 B**，含 `CLASS`/`FIELD` 真实映射 |
| 本机 26.3 客户端 jar | 41,483,720 B / 34,227 条目 / **10,737** 个 `net/minecraft/*.class`；根目录短名混淆类 `^[a-z]{1,3}\.class$` = **0 个** |

⇒ 「往前支持」不是一条连续的难度曲线，而是**两段**：

| 区间 | 映射机制 | 构建链 | 相对成本 |
|---|---|---|---|
| **26.x 线内**（26.1 ↔ 26.3） | 全部 official 名，零映射 | 同一套（Loom 1.18 slim / FG7 / MDG 2.0.148） | **低**：只有 API 增减与改名 |
| **跨到 1.21.11 及更早** | Yarn / intermediary / Mojang official + remap | **另一套**：`refmap`、reobf、`remap=false` 全部回来；Loom 不能再用 slim 版 | **高**：≈ 重做整条构建链，且 Java 21（1.21.11）× Java 25（26.1+）再分一次 |

**结论**：支持范围按「26.x 及以后」划线。**1.21.11 及更早不属于「往前支持」，而属于「再做一个版本线」** —— 它需要独立的分支树 + 独立的构建配置 + 独立的映射依赖，指望在同一条 `main` 上演进过去是不成立的，会同时推翻 §2 B4 的 API 基线与 §11.2 R5（不写死类名）—— 因为在 remap 体系下类名本来就不是稳定的契约。

这一条也解释了为什么本项目「最大的一次简化」是去混淆（前置文档 §2.2）：它同时消掉了映射表、refmap、reobf 三样东西，并把类名变成了跨版本稳定的契约。

### 11.5 升级 checklist：跟新版本（流程 A，`main` 前进，例如 26.3 → 26.4）

1. **先决策**：还要不要继续支持旧版本？要 ⇒ 从当前 `main` 切出保护分支 `mc<旧版本>`（如 `mc26.3`）并冻结；不要 ⇒ 跳过此步，直接前进（大多数公开工具都走这条）
2. 跑 `tools/nms-surface.mjs` 拿到引用面基线
3. 三个 `platform/*` 和 `shared` 分别编译，按编译错误修 `net.minecraft.*` 名字
4. 检查 mixin `compatibilityLevel`（Java 版本可能又跳了）
5. 检查三家的 `displayTest` / `PayloadRegistrar` 机制有没有再变（前置文档 §8.2 已记录 Forge 与 NeoForge 分叉）
6. 检查 gradle 任务名（尤其是 GameTest）
7. 重跑 P0 的 V1–V4、V6–V10
8. **检查 API 基线**：`shared/` 用到的 API 是否都还在（B4）；若新版本删了某个 API，决定是改写还是把该功能标 `[version-only: 旧版]`
9. 更新 `docs/` 里的版本矩阵与 `platform/*/gradle.properties`

**工作量函数**：≈ 第 3 步的编译错误数 × 单个修复成本。这就是 B4 要求计量引用面的原因 —— 它让「这次升级要几天」在动手前有数字。

### 11.6 往前补做旧版本（流程 B，**仅适用于 26.x 线内**）

> **适用范围**：26.2 及 26.1.x。**不适用于 1.21.11 及更早** —— 那是另一条版本线，见 §11.4。如果在 1.21.x 上照搬本流程，会在第 2 步之前先撞上「没有映射表」这一层，整套构建链都要换。

**比升级难**：升级遇到「API 改名」，往前补遇到「API 不存在」。

1. 从最近的较高版本分支切出 `mc<目标版本>`（如从 `mc26.3` 切 `mc26.2`）
2. 改版本号 → 编译 → **这一步会遇到「API 不存在」而不是「API 改名」**，需要重写而非改名字
3. 逐条决定缺失 API 的对策：换等价旧 API / 降级实现 / 该功能在本版本上标不可用
4. 收缩 API 基线（B4）：`shared/` 从此受 26.2 ∩ 26.3 约束，**这条约束要反向应用到 `main` 与 `mc26.3`**
5. 重跑 P0 与完整测试矩阵

**第 4 步是往前支持真正的成本**：每补一个更旧版本，所有分支的可用 API 面都收缩一次。所以「往前支持」要么一次性定好最低版本，要么接受反复收缩。

---

## 12. 增删功能的操作手册

### 加一条路由（最常见）

1. 在对应 Module 的 `register()` 里加一行 `routes.add(...)`
2. 写处理器方法

**不碰**：`/status`（traits 自动派生）、文档（`/meta/routes` 自动生成）、任何其他路由。

### 加一个能力域

1. `Trait` 枚举加一个值
2. 新 Module 类（~50 行）
3. `Modules.all()` 加一行
4. 如果该能力只在一端 → 在 `Module.side()` 声明，框架自动处理另一端的 501 与 `/status` 的缺失

### 删一个能力域

1. `Modules.all()` 删那一行
2. 删 Module 类
3. `Trait` 删那个值（编译器会帮你找漏掉的引用）

**能力声明、路由、命令、文档全部跟着消失** —— 因为它们是派生的，不是手写的。这正是不变量 I3 的价值。

### 加一个动作

1. 在 `action/` 加动作类型 + 前置声明（`GameType` / trait）
2. `ClientActionBackend` 实现
3. `ServerActionBackend` 实现（若服务端不支持，返回 `TRAIT_UNAVAILABLE` 并在 `coverage()` 里标 `none`）

### 加一个加载器

见 §9.1 的酸性测试。

### 升级 MC 版本

见 §11.5 checklist。

### 回迁一个功能到旧版本分支（★ 预案，现在没有目标分支）

> **当前不适用**：只有 `main`（== 26.3），没有可回迁的目标分支。本节等到切出第一条 `mc26.3` 之后才启用。

1. 在 `main` 上确认该 commit 带 `[backport: mc26.3, mc26.2]` 标记（§11.1 约定）
2. `git cherry-pick <sha>` 到每条目标分支
3. **冲突处理判据**：冲突落在「这个功能自己的 Module 文件 + 注册表一行」以内 ⇒ 正常解决；**落在别处 ⇒ 说明 I7 被违反了，回去把 main 上的改动重新拆成局部改动**，不要在冲突里硬解
4. 在目标分支上跑该版本的测试矩阵（3 加载器 × 该版本），只改该版本真正需要改的（版本专属修复打 `[version-only]` 的反向标记）
5. 若冲突无法廉价解决 ⇒ 该功能在此分支标为「不支持」，并在 `/status` 的 `traits` 里自然缺失（I3 保证不会撒谎）

**判据的用处**：第 3 步把「架构不变量」变成了「回迁时立刻可感知的痛」。I7 不是风格偏好，它是让模式 C 可持续的唯一手段。

### 加一条路由时注意回迁友好

按 §12 第一条做的前提下，额外确认：新增的 `Route` 行追加在**该 Module 注册表末尾**（不在中间插入），trait 枚举值也追加在末尾。这不是美观问题 —— 中间插入会让每条分支的 cherry-pick 都撞同位置。

---

## 13. 反模式清单

这些做法在 review 时必须被拒：

| 反模式 | 为什么 | 替代 |
|---|---|---|
| 在共享库里写 `if (loader == "forge")` | 违反 I1，共享库开始知道加载器 | 放进 `platform/`，或用 `RegistryBridge` |
| 手写 `/status` 的 `capabilities` / `traits` 数组 | 违反 I3，必然漂移 | 从注册表派生 |
| 在探针里判断「测试是否通过」 | 违反 I4，探针变成测试框架 | 探针返回数据，断言放 `src/assert/` |
| 给某条路由加 `if (side == SERVER) return ...` | 违反 I6，端归属不再显式 | 在 `Module.side()` / `Route.side()` 声明 |
| 为了兼容旧路由名保留两套路径 | 维护双倍，且调用方永远不知道用哪个 | 一次性改名，改调用方 |
| 在 `core` 里 import 业务模块 | 违反依赖矩阵，`core` 变成上帝模块 | `core` 只认「路由表」抽象 |
| 三份 platform 里出现「只改了一行」的复制粘贴共享类 | 违反 I1，等于把共享库拆成三份 | 移到 `shared/`，用 `RegistryBridge` 接 |
| 用 `/command` 冒充动作 | 违反 B5 的语义区分，测不出动作路径 | 走 `/action/*`，路径真的走一遍 |
| 一个功能顺手改到别的 Module / `core` | 违反 I7，模式 C 下每次回迁都变成人工冲突 | 拆成两个 commit：本职改动 + 独立的重构改动（后者打 `[version-only]` 或单独回迁） |
| 在 `shared/` 用只有最新版本才有的 API 却不标注 | 违反 B4 的 API 基线，往前支持时找不到该改哪里 | 注释标 `// 26.3+` 并集中在一处；或改用稳定老 API |
| 用反射或字符串写 `net.minecraft.*` 类名（`Class.forName("net.minecraft...")`） | 违反 R5：版本间改名时**编译期看不见**，变成运行时静默失败或 `NoClassDefFoundError` | 直接 import 类型；真需要动态时也走类型 token 而非字符串 |
| 把 mod 元数据的版本约束放宽成 `>=26.1` 以求「一个 jar 通吃」 | 违反 N5：跨版本加载只会更晚崩、报错更难懂 | 写死精确版本（`~26.3`），一个版本一个构建 |
| 为「将来可能的多版本」提前引入 stonecutter / preprocessor / 复合构建 | 违反 N1–N3：P0 未验证时叠层 = 双重风险，且 N=1 纯亏 | 按 §11.2 六条零成本纪律预留，触发条件到了再上 |

---

## 14. 规模预算

| 部分 | 行数 | 说明 |
|---|---:|---|
| `shared/core` | 400-500 | HTTP / JSON / 路由表 / 错误 / tick / 日志 |
| `shared/module` | 220 | Trait / Module / RouteRegistry / Modules 表 |
| `shared/world` + `inventory` + `craft` | 700-800 | 移植自原 1,114 行减去 core |
| `shared/action` | 600-900 | 语义层 + 后端接口 + 覆盖矩阵 |
| `shared/client` | 600-700 | 屏幕 / 输入 / 截图（移植自 `ClientProbeServer` 788 行） |
| `platform/fabric` | 60-80 | 入口（FakePlayer 另计，见下）。**P1 实测入口 52 行** |
| `platform/forge` | 70-100 | 同上（EventBus 7，最费事） |
| `platform/neoforge` | 60-80 | 同上（FakePlayer 现成） |
| **Java 小计（不含可选项）** | **2,710-3,420** | 比上版少 160-180 行：`RegistryBridgeImpl` 不需要了 |
| `probe-plugin` | 1,800-2,200 | 14 工具 + 编排 + 断言 + 探测 + 三个 Launcher |
| **总计（不含可选项）** | **≈ 4,500-5,620** | 原项目 1,405（JS）+ 1,902（Java）= 3,307 |
| *可选：三平台 FakePlayer* | *+300-900* | *只在「多玩家并发」与「无客户端的 CI 自动化」需要（§8.2）* |

规模约为原项目的 **1.35-1.7 倍**。增量主要来自 §8 的动作层与 §10 的编排/断言，而不是加载器适配（那部分只占 190-260 行，比上一版又少了一截）。

**P1 实测对预算的两处修正**：
1. 原探针 1,114 行里真正落到 `shared/` 的是 **934 行**（`ServerLevelMixin` 26 行不需要了，`ProbeCommands` 的 `fakeplayer` 子命令随假玩家一起移走）。
2. 共享库**零 mixin**，所以 `compatibilityLevel` / `refmap` / 三家注册通道这些成本在 P1 都没有发生 —— 它们在「将来真要写 mixin」时才需要，那时再查 §9 那张表。

**FakePlayer 的可选化**（用户定「角色操作在客户端实现」的直接结果）：上一版把三平台 FakePlayer 算进必做，现在移出。若将来要补，三平台各 100-300 行，NeoForge 有现成 API 最省，Forge 需自建（`FakePlayerFactory` 已移除）。

---

## 附录 A：与原探针的结构对照

| 原结构 | 新结构 | 变化原因 |
|---|---|---|
| `ProbeServer` 一个类 646 行 / 15 路由 | `core` + `world` / `inventory` / `craft` 三个 Module | 可删除性：删一个能力域不动其他 |
| `ClientProbeServer` 一个类 788 行 / 17 路由 | `client/screen` + `client/input` + `client/capture` | 同上；且方便 I2 物理隔离 |
| `handleNotClient` 三条路由共用一个拒绝函数 | 路由不注册 → 路由表统一拦截 | 少一处特例，`/status` 与实际能力一致 |
| `ProbeCommands` 一个类 6 个子命令 | 拆进 CORE / FAKE_PLAYER 两个 Module | 删假玩家时命令跟着消失 |
| 无 `/status` 能力字段 | `traits` + `actions` 派生 | 负向断言与能力协商的前提 |
| 无 `/meta/routes` | 自描述路由表 | 文档不漂移 |
| 无动作抽象 | `/action/*` + 双后端 | 「全操作」需求的结构化落点 |

## 附录 B：三个决定的定稿状态

| # | 决定 | 状态 | 落点 |
|---|---|---|---|
| 1 | §7.4 路由改名（`/ui/*` 拆成 `/container/*` + `/ui/*`） | **已采纳**（m00625） | §7.4 / §8.3 |
| 2 | §8.2 服务端定位：观察 + 环境控制，角色操作在客户端 | **已定**（m00625 原话） | §8.2 |
| 3 | §11.1 分支策略 | **已改为模式 C：多分支并行**（m00718 原话："之后游戏更新新的正式版之后肯定也要支持到新版本，并且后续开发增删的功能也得同步到所有支持的版本中"） | §11.1 / §11.2 / I7 / B4 |

**决定 2 的直接后果**：`FakePlayer` **不进 P0**，降级为可选增强 —— 只在「多玩家并发」与「无客户端的纯服务端自动化（如 CI 跑 GameTest）」时需要。前置文档 §13.1 的 V5、§14 风险 #2 已相应降级。

---

*本文件为架构产物，不含实现。任何实现细节与本文档冲突时，以本文档为准；确需偏离时必须先改本文档，说明理由。*
