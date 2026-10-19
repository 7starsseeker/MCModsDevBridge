# 场景配方（scenario-cookbook）

`scenarios\*.json` 的写法。本文件是**模板库**：照抄改坐标/物品即可，不用重新设计结构。

## 0. 骨架与匹配语义

```json
{ "name": "my-check", "description": "一句话说清这条场景在证明什么",
  "steps": [
    { "id": "unique-step-id", "target": "server", "method": "POST", "path": "/command",
      "body": { "cmd": "say hi" },
      "expect": { "status": 200, "json": { "ok": true } } }
  ] }
```

| 字段 | 作用 |
|---|---|
| `id` | 步骤名，做证据时按它定位；必须唯一 |
| `target` | `server` / `client`，决定打到哪个探针 |
| `body` | 对象写进请求体（JSON），字符串原样发 |
| `expect.status` | 期望 HTTP 状态码 |
| `expect.json` | 点路径**精确相等**（`data.block`、`error.code`） |
| `expect.contains` | 点路径值**包含子串**（用来看「列表里有它」「文本里有这句」） |
| `expect.text` / `textNot` | 对**整个响应文本**做包含/不包含（专用于 `/log/tail`） |
| `retry` | `{times, intervalMs}`：每次尝试都重新发请求（跨端传播时用） |
| `waitMs` | 收到成功响应后**再等**这么久，用于等游戏内副作用（走路、下落、动画） |
| `note` | 给读结果的人看的备注 |

`{{player}}` 会被替换成当前玩家名，**不要写死名字**（Fabric 每次启动名字都变）。

## 1. 前置造景 → 动作 → 回读（最重要的三条）

```json
{ "id": "provision-forceload", "target": "server", "method": "POST", "path": "/command",
  "body": { "cmd": "forceload add -20 -90 20 -70" }, "expect": { "status": 200, "json": { "ok": true } } }
{ "id": "provision-stone", "target": "server", "method": "POST", "path": "/command",
  "body": { "cmd": "setblock 11 72 -79 minecraft:stone" }, "expect": { "status": 200 } }
{ "id": "read-back-stone", "target": "server", "method": "GET", "path": "/world/block?x=11&y=72&z=-79",
  "expect": { "status": 200, "json": { "data.block": "minecraft:stone" } } }
```

要点：**远处的方块必须先 `forceload`**，否则区块没加载，`/world/block` 读到的是 air 或者报错；
做完把 forceload 清掉（`forceload remove 8 8`）别留给下一轮。

## 2. 跨维度

服务端动作**没有**「在哪个维度」参数，维度靠原版命令前缀带：

```json
{ "id": "dim-setblock", "target": "server", "method": "POST", "path": "/command",
  "body": { "cmd": "execute in minecraft:the_nether run setblock 8 100 8 minecraft:stone" } }
{ "id": "dim-read", "target": "server", "method": "GET",
  "path": "/world/block?dim=minecraft:the_nether&x=8&y=100&z=8",
  "expect": { "status": 200, "contains": { "data.block": "stone" } } }
```

而 `/world/*` 用 `?dim=` 查——**两边必须一致**，否则读到的是主世界的同一个坐标。
注意 `contains` 而不是 `json`：`data.block` 可能带 `minecraft:` 前缀也可能不带，用子串更稳。

## 3. 跨端传播：聊天的「最终可见」只有客户端能证明

```json
{ "id": "say", "target": "server", "method": "POST", "path": "/command", "body": { "cmd": "say probe-chat-line" } }
{ "id": "client-chat", "target": "client", "method": "GET", "path": "/chat/tail?n=40",
  "retry": { "times": 20, "intervalMs": 250 },
  "expect": { "status": 200, "contains": { "data.text": "probe-chat-line" } } }
{ "id": "server-log", "target": "server", "method": "GET", "path": "/log/tail?n=80",
  "expect": { "text": ["probe-chat-line"] } }
```

- 服务端 `say` 成功**只证明服务端执行了**，不证明客户端收到 ⇒ 必须回读客户端。
- 传播有延迟 ⇒ 用 `retry`（20×250ms = 5s 预算），不要用 `waitMs` 猜。
- 动作栏（`/title @p actionbar`）在客户端是独立字段 `data.actionbar`，不在 `data.text` 里。

## 4. 负例：验证「该失败的时候真的失败」

```json
{ "id": "dim-unknown", "target": "server", "method": "GET",
  "path": "/world/block?dim=minecraft:definitely_not_a_dimension&x=8&y=100&z=8",
  "expect": { "status": 400 } }
{ "id": "loud-failure-unknown-gamemode", "target": "server", "method": "POST", "path": "/action/gamemode",
  "body": { "mode": "nonsense" }, "expect": { "status": 400, "json": { "error.code": "BAD_REQUEST" } } }
```

负例断言**错误码**（`error.code`），不要只断 400——400 可能来自参数形状错误，语义完全不同。

## 5. 服务端动作层（9 个）

```json
{ "id": "gamemode-creative", "target": "server", "method": "POST", "path": "/action/gamemode",
  "body": { "mode": "creative" }, "expect": { "json": { "data.applied": true } } }
{ "id": "server-mine", "target": "server", "method": "POST", "path": "/action/mine",
  "body": { "pos": [11, 72, -79], "face": "east" }, "expect": { "json": { "data.broken": true } } }
{ "id": "server-place", "target": "server", "method": "POST", "path": "/action/place",
  "body": { "pos": [13, 71, -79], "face": "up" },
  "expect": { "json": { "data.changed": true, "data.blockBefore": "minecraft:air" } } }
```

- `mine` / `place` / `use` / `attack` 都要 `pos` + `face`（`up|down|north|south|east|west`），
  `place` 用玩家主手物品，所以**先给物品**（见 §6）。
- 返回值里有 `broken` / `changed` / `blockBefore` 这类"做了什么"字段，别只断 200。

## 6. 背包

```json
{ "id": "inventory-clear", "target": "server", "method": "POST", "path": "/action/inventory",
  "body": { "op": "clear" }, "expect": { "json": { "data.op": "clear" } } }
{ "id": "give-stone-to-hotbar0", "target": "server", "method": "POST", "path": "/action/inventory",
  "body": { "op": "give", "item": "minecraft:stone", "count": 8, "slot": 0, "select": true },
  "expect": { "json": { "data.added": true, "data.selectedSlot": 0, "data.item": "minecraft:stone" } } }
```

`/action/inventory` 的 op：`give` / `clear` / `set-selected` / `click` / `slots` / `drop` / `close` /
`button` / `trade` / `rename`。每条场景开头先 `clear`，否则上一轮残留物品会让断言飘。

## 7. 客户端动作层（12 个）

```json
{ "id": "walk-platform", "target": "server", "method": "POST", "path": "/command",
  "body": { "cmd": "fill -2 80 -2 2 80 2 minecraft:stone" } }
{ "id": "walk-teleport-to-platform", "target": "server", "method": "POST", "path": "/command",
  "body": { "cmd": "tp {{player}} 0.5 81 0.5" } }
{ "id": "client-walk", "target": "client", "method": "POST", "path": "/action/move",
  "body": { "mode": "walk", "durationMs": 400 }, "waitMs": 900,
  "expect": { "json": { "data.moved": true } } }
```

- 客户端动作用 `waitMs`（副作用在游戏循环里继续跑），跨端传播用 `retry`。
- 走路前必须**先把玩家放到平台上**（`tp {{player}} x y z`），否则掉虚空，后续步骤全废。
- `mode`: `walk` / `sprint` / `sneak` / `jump` 等；`spectate` / `respawn` 独立端点，用完记得退出状态。
- 客户端动作的返回值大多只是「我发出去了」（`moved: true`），**真实落点要用服务端 `/player/state` 回读**。

## 8. 单人世界（不连服也能验客户端）

交互类场景（GUI 点击、背包界面、告示牌编辑）常常不需要服务器：

```json
{ "id": "singleplayer", "target": "client", "method": "POST", "path": "/world/singleplayer",
  "body": { "worldName": "mcdev-sp" }, "retry": { "times": 40, "intervalMs": 500 },
  "expect": { "status": 200 } }
```

起单人世界比连服慢得多（要生成地形），`retry` 给足预算；场景结束用 `/world/leave` + `/quit`。
用 `side: client` 的实例（`dir` 空、`gamePort` 0）跑这类场景。

## 9. 常见错法

| 错法 | 后果 | 正确做法 |
|---|---|---|
| 远处方块不 `forceload` | 读到 air / 500，还以为是 bug | 先 `forceload add`，结束后 `forceload remove` |
| 用 `waitMs` 等跨端传播 | 机器一慢就随机失败 | 用 `retry`（每次都重新请求） |
| 只断 `status: 200` | 接口通了但什么都没发生 | 再断 `data.*` 的语义字段 + `/world/block` 回读 |
| 断服务端 `say` 成功就说「客户端收到了」 | 假绿 | 必须打到客户端 `/chat/tail` |
| 写死玩家名 | 换一次启动就找不到玩家 | `{{player}}` |
| `place` 前不给物品 | 放下的是空手，方块不变 | 先 `inventory give ... select: true` |
| 场景之间不清理 | 上一轮的 forceload / 物品 / gamemode 残留 | 每条场景自建自清 |
