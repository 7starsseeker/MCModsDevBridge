# 世界状态断言：方块 / 方块实体 / 实体 / 位置（mc-world-assertions）

给「我要断言游戏世界真的变成了这样」用的。命中词：断言方块、回读、setblock 没生效、result:0、
区块没加载、实体没杀掉、位置不对、朝向、睡眠、方块实体、告示牌、命令方块。

端点参数以 `mcdev-probe-protocol` 技能里的那份端点全表为准；这里讲**怎么判**和**为什么误判**。

## 0. 一句话

**命令/动作返回成功 ≠ 世界改变了。** 只有「换个视角回读」才是证据，而回读本身有四个噪声源（§2、§3）。

## 1. 三种静默失败（都真的踩过）

| 现象 | 真相 |
|---|---|
| `setblock` 回 `result:0` | **`result` 不是成功布尔**。目标远在未加载区块时命令**静默失败**，仍回 200 |
| `/command` 回 `ok`，但方块没变 | 副作用有前置条件（区块加载 / 实体落地 / 模式切换）。回执只证明「命令执行了」，不证明「效果发生了」 |
| `kill @e[type=minecraft:zombie]` 回成功，僵尸还在 | 选择器**只作用于已加载的实体**。远处执行时测试区还没加载 ⇒ 清怪清了个空气，僵尸在玩家 `tp` 落地后才出现 |

第一条的后果很具体：曾有一整轮 `sleep` 时好时坏，`landed=true`、离床 0.7 格、结果 `Success[SERVER_ONLY]`
却 `sleeping=false`——根因就是上面第三行的僵尸。

**修法不是加断言，是加前提**：`gamemode`/`tp` → 轮询 `/player/state.onGround` 直到 true（最长几秒）
→ **再** `kill` → `time set midnight` → `sleep`。凡是带「加载 / 落地 / 模式」前提的操作，**先等一个可观测前提**。

## 2. 回读权威性分四层

| 层 | 读什么 | 能证明什么 |
|---|---|---|
| 服务端世界 | `/world/block`、`/block/sign`、`/block/command-block`、`/entity/list`（服务端） | **权威**：世界真正的状态 |
| 客户端视角 | `/entity/list`（客户端）、`/ui/snapshot`、`/block/sign`（客户端侧） | 「玩家看到了什么」：同步/渲染侧是否一致 |
| 客户端动作 | `/action/mine`、`/action/place`、`/player/use`… | **只证明发了动作**。客户端说「挖了」，必须拿服务端 `/world/block` 回读才算数 |
| 加载器 | `/mods` | 装没装上（加载器给的清单，不是探针猜的） |

所以一条「玩家挖掉了方块」的正确断言是**两条**：客户端动作成功 + 服务端方块变了。只写前者，
任何同步 bug 都能骗过你。

## 3. 未加载区块：最常见的假阴性

- 读远处方块得到「旧值」或「空」，不一定是没写进去，可能是**区块没加载**。
- 判「没变」之前，先确认玩家在附近（`/player/state` 的坐标）或区块确实加载。
- 反过来也一样：**写**远处方块可能静默失败（见 §1）。写 + 读都发生在远处时，两个错误会互相掩护。

判据顺序：**先保证在场**（`tp` 到目标附近 / 等 `onGround`）→ 再动作 → 再回读。

## 4. 位置与朝向：别用单点判等

坐标判等会被重力和插值骗。拆成三个字段（这是本仓踩出来的）：

| 字段 | 含义 | 用途 |
|---|---|---|
| `horizontalError` | 水平差 | **判据**（≤0.75 视为到位） |
| `verticalDrift` | 竖直漂移 | 只记录，**不算失败**（刚落地会掉 0.7 格） |
| `snappedBack` | 是否回到起点附近（≤0.75） | **这才是「服务端拒绝了」的证据** |

只比 `distanceTo(target)` 会把「落地掉 0.7 格」误判成「被拒」。朝向同理：yaw/pitch 存成归一化角度再比，
别直接比 359° 与 −1°。

## 5. 断言模板

```jsonc
// 方块真的变了（服务端权威）
{ "id": "block-placed", "target": "server", "path": "/world/block?dim=overworld&x=0&y=64&z=0",
  "expect": { "notEquals": { "data.block": "minecraft:air" } } }

// 方块真的没变（先保证在场，否则这是假阴性）
{ "id": "block-untouched", "target": "server", "path": "/world/block?dim=overworld&x=0&y=64&z=0",
  "expect": { "equals": { "data.block": "minecraft:stone" } } }

// 实体被清掉（注意：只作用于已加载实体，先 tp 到场）
{ "id": "no-zombies", "target": "server", "path": "/entity/list?dim=overworld",
  "expect": { "notContains": { "data.ids": "minecraft:zombie" } } }

// 告示牌/命令方块内容：服务端才是回读权威
{ "id": "sign-text", "target": "server", "path": "/block/sign?dim=overworld&x=0&y=64&z=0",
  "expect": { "contains": { "data.lines": "hello" } } }

// 跨端对称：客户端看到了，服务端也认
{ "id": "both-know", "target": "client", "path": "/entity/list", "expect": { "contains": { "data.ids": "minecraft:zombie" } } }
```

字段名（`data.block` / `data.ids` / `data.lines`）以 `endpoints.md` 与实际响应为准；先 `GET` 一次看真实形状再写断言，
别照抄本文件的猜法。

## 6. 边界

- **方块状态属性与方块实体 NBT 是两次回读**：`/world/block` 给方块/状态，`/block/sign`、`/block/command-block`
  给方块实体内容。别指望一个端点两者都给你。
- **跨维度要显式给 `dim`**：默认维度不是「你刚才在哪」。
- **单人世界 ≠ 专用服**：单人世界里客户端即服务端（同一进程），双端断言在单人下会同时成立——想验证
  「只有一侧装了会怎样」，必须用专用服（见 `mcdev-fixture-design`）。
- **客户端 `/block/sign/edit` 要等一 tick**才稳定，编辑完立刻回读可能拿到旧值：用 `retry`，别用 `waitMs` 硬等。
