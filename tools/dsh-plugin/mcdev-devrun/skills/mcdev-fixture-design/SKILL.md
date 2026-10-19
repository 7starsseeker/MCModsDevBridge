# fixture 设计与「缺一半」证据（mcdev-fixture-design）

给「要证明一个 mod 的某一侧真的生效了 / 要证明它缺了一半」用的。命中词：fixture、只装一半、server-only、
client-only、跨端、握手、日志证据、`/mods` 对照、加载 ≠ 生效、跨加载器同一份场景。

## 0. 为什么需要一套专门的 fixture

**缺一半通常不报错。** 装错加载器的 jar，加载器只是**静默跳过**——游戏照常启动，唯一差异是 `/mods` 的 ids
少一个（实测 `minecraft, mcdev_probe, forge`）。同样，只写了客户端入口的 mod 丢进专用服务器，也不会崩，
只是那一半根本没跑。

所以「缺一半」不能靠「控制台没红字」判断，得让 **mod 自己留下一行只有「该在的都在场」才会出现的日志**。

## 1. 装配矩阵：五个工程各证明一件事

| 工程 | 端装配 | modId | `environment` | 它证明什么 |
|---|---|---|---|---|
| `lab\fixtures\fixture` | **both**（真两半） | `mcdev_fixture` | `*` | 正常路径：两侧握手能走通（基线） |
| `lab\fixtures\fixture-client` | **client-only**：只有 `src\client\java`，**没有 `src\main\java`** | `mcdev_fixture_client` | `client` | 装到专用服务器时「服务端那半边缺席」是什么现象 |
| `lab\fixtures\fixture-server` | **server-only**：只有 `src\main\java` | `mcdev_fixture_server` | `server` | 客户端缺席时的现象 |
| `lab\fixtures\fixture-forge` | both，**单 source set** + `dist == CLIENT` 守卫 | `mcdev_fixture` | `mods.toml side="BOTH"` | 同一套判据在 Forge 上成立 |
| `lab\fixtures\fixture-neoforge` | 同 Forge | `mcdev_fixture` | 同上 | 同上，NeoForge |

三条设计要点：

1. **三个 both 工程共用同一个 modId 与同一串日志文本**（`mcdev_fixture`）⇒ 同一份场景 JSON 跨加载器复用，
   不用为每家改断言。
2. `fixture-client` / `fixture-server` 是**故意残缺**的：前者连 `src\main\java` 都没有，后者没有客户端入口。
   它们不是半成品，是探针。
3. 五个 fixture **都没有 Screen/GUI 类、也没有业务功能**，只有握手日志——fixture 里塞功能，失败时就分不清
   是「机制坏了」还是「功能写错」。

Fabric 靠 `fabric.mod.json` 的 `entrypoints.client/server` 分端；Forge/NeoForge 只有一个 main source set，
所以客户端入口只能和主代码同 jar，用**构造器里的 `dist == CLIENT` 守卫**才 `install()`。

## 2. 判据分四层，顺序不能反

| 层 | 问什么 | 证据 |
|---|---|---|
| 1 装没装上 | 加载器有没有加载它 | **两侧各查一次** `/mods` 的 `contains` / `notContains`。`/mods` 是加载器给的权威清单，不是探针猜的 |
| 2 生效没生效 | 两侧真的握手了吗 | mod 自己的三行闭环：`client sent hello` → `server got hello from` → `client got ack` |
| 3 **缺的是哪一半** | 缺席的那侧是谁 | **对端自己的超时行**：`client missing for <player> (no hello in 100 ticks)` / `server missing (no ack in 120 ticks)` |
| 4 加载 ≠ 生效 | 只是被加载，还是在跑 | 拆成三行：`client side active` → `joined world (client-only half)` → `tick probe alive (client-only)` |

第 3 层是这套方法的**核心**：正向的「X 缺席」比反向的「日志里没有 Y」强得多——后者只能靠人眼确认，
前者可以 `expect.contains` 直接断言。场景里同时写 `contains`（握手行）与 `notContains`（不该出现的行）才是对称的。

第 2 层有个坑：**actionbar 不算握手回执**，只有 `overlay == true` 的真系统消息才算；要断言 actionbar
就单独走 `/chat/tail` 的 `actionbar` 字段，别把它当 ack。

## 3. 用场景把结论钉住

```json
{ "id": "both-side-mods",   "target": "server", "path": "/mods",
  "expect": { "contains": { "data.ids": "mcdev_fixture" } } }
{ "id": "client-side-mods", "target": "client", "path": "/mods",
  "expect": { "contains": { "data.ids": "mcdev_fixture" } } }
{ "id": "handshake",        "target": "server", "path": "/log/tail?n=120",
  "retry": { "times": 20, "intervalMs": 250 },
  "expect": { "text": ["server got hello from", "client got ack"] } }
```

现成场景（本仓 `scenarios\`）：`fixture-both.json`(5) `/ fixture-client-missing.json`(4) /
`fixture-server-missing.json`(5) / `fixture-client-only.json`(6，`side:client` 单人世界) /
`fixture-client-only-server.json`(4)。

**别混用**：给 `side: server` 实例写的契约用例（如 `server-only.json`）拿到双端实例上跑**必然 FAIL**
（期望 404 实得 200）。那不是 bug。

## 4. 已知边界（写文档/报结论时必须带上）

- **Forge / NeoForge 的客户端那一半只过了编译**：`client sent hello` / `client got ack` 与三条 `fixture-*`
  场景**只在 Fabric 实跑过**；两家客户端分支未实跑。
- 两条超时路径（`client missing` / `server missing`）**也只在 Fabric 验过**。
- 单人世界里验的 `fixture-client-only` 只证明「装在同一份客户端里时生效」，**不等于**「专用服务器没装时的表现」。
- fixture 全部不含 GUI：**GUI 相关的缺半边现象不在覆盖范围**。

## 5. 三个错法（都真的踩过）

1. **只订一个聊天事件**：Forge 只订 `ClientChatReceivedEvent`，而命令回执走另一个事件 ⇒ 「ack 到了，没人接」。
   两个都订（`ClientChatReceivedEvent` + `SystemMessageReceivedEvent`）。本 fixture 第一版就是这么错的。
2. **把 actionbar 当回执**：见 §2。
3. **只证「日志里没出现 X」**：那是反证，靠人眼；要写正向的「对端缺席」行。

## 6. 什么不该进证据链

**截图不是判据。** 本仓曾做过「按步骤截图、失败自动拍」，后来**撤掉了**：判定只来自探针响应、日志行、
方块/背包/实体回读。画面类现象（紫屏动画、光影、音效、F3 读数）属于「必须人眼」，不塞进编排——
硬塞的结果是「拍了很多图但没人看」。
