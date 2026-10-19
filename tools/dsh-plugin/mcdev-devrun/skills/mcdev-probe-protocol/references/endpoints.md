# 探针端点全表（26.3，按能力分组）

> 来源：`lab/shared/src/main/java/com/mcdev/probe/ProbeServer.java`（路由注册 88-111 行）与
> `lab/shared/src/client/java/com/mcdev/probe/ClientProbeServer.java`（100-130 行）。
> 括号里是 `capability` 标签，会聚合进 `/status.data.capabilities`。
> 参数列只写**确定的**；标「见源码」的表示参数集随实现演化，别按记忆写。

## 服务端探针（默认 39001）

| 方法 | 路径 | 能力 | 用途 |
|---|---|---|---|
| GET | `/status` | core | 能力发现入口：side / loader / capabilities / actionBackend / actions / tick / frozen / stepBudget / version |
| GET | `/mods` | core | 已加载 mod 清单（`data.ids`、count、source），用来证明装上了/卸干净了 |
| GET | `/log/tail?n=` | core | 服务端日志尾部（按行取，n 条） |
| POST | `/command` body `{cmd}` | command | 执行一条服务端命令；`ok:true` 仅代表被接受 |
| GET | `/world/block?dim=&x=&y=&z=` | world | 读某个维度的方块（`data.block`） |
| POST | `/world/break?dim=&x=&y=&z=` | world | 破坏方块（`data.removed`） |
| POST | `/world/mine?...` | world | 破坏并取掉落（见源码） |
| GET | `/items?x=&y=&z=&r=&dim=` | inventory | **附近的掉落物（`ItemEntity`）聚合**，`r` 上限 16，返回 `drops[]`/`total`——**不是**物品注册表 |
| GET | `/item?item=&nbt=` | inventory | **HUD 条渲染数据**（`visible`/`width`/`color`），不是物品注册表查询 |
| GET | `/recipe?id=` | craft | 配方产物查询。id 非法 → `found:false` + `error:"invalid recipe id"`；**配方不存在 → 只有 `found:false`，没有 `error`** |
| POST | `/craft?recipe=&player=` | craft | 按配方**扣背包材料、给产物**（结果 `crafted`/`consumed`/`missing`/`result`）。**不是真实合成流程**，详见 `mc-content-data-verification` |
| GET | `/loot?block=&x=&y=&z=` | craft | 方块**默认状态**的掉落求解（固定 overworld、`tool=null`，不吃时运/精准采集） |
| GET | `/player/state` | player | 玩家位置/维度/gamemode/血量等；玩家不在线回 `error: player not online` |
| GET | `/entity/list` | entity | 维度内实体列表 |
| GET | `/block/sign?...` | block_extra | 读告示牌内容（服务端才是回读权威） |
| GET | `/block/command-block?...` | block_extra | 读命令方块 |
| GET | `/run/dump` | core | 这次运行的记录（`RunRecorder`）快照 |
| POST | `/action/<name>` | action | 服务端动作后端（见下表） |
| * | `/screenshot` `/ui/*` | — | **501 client-only**，显式告诉你打错一侧了 |

服务端动作后端（`ServerActionBackend.actions()`，9 个）：
`teleport` `gamemode` `ability` `mine` `place` `use` `attack` `pick` `inventory`

> 服务端这 9 个是「服务端天然权威」的动作：不需要客户端输入，但**需要玩家真的在线**
> （作用在真实的 `ServerPlayer` 上，不是假玩家）。玩家离线时回 `error: player not online`。

## 客户端探针（默认 39002）

| 方法 | 路径 | 能力 | 用途 |
|---|---|---|---|
| GET | `/status` | core | `side=client`、`loader`、`actionBackend`、`linked`（已连上服务器）、`inWorld`（已进世界）、`screen`（当前界面类名，如 `TitleScreen` / `BackupConfirmScreen`）、`dimension`、`playerPosX/Y/Z`（数值坐标）、`playerPos`（`Vec3.toString()` 字符串，只供显示） |
| GET | `/mods` | core | 客户端已加载 mod |
| GET | `/log/tail?n=` | core | 客户端日志尾部 |
| POST | `/connect?host=&port=` | net | **只认 query**；把客户端接到某个服务器 |
| POST | `/world/singleplayer` | world | 进单人世界：query `name` / `mode=normal\|flat` / `gamemode` / `allowCommands` / `seed` / `reuse` / `backup=ask\|skip\|do`；返回 `levelId` / `worldDir` / `renamed` / `openedExisting` / `backupAction` |
| POST | `/world/leave` | world | 退出当前世界 |
| GET | `/ui/snapshot` | client_ui | 读当前 `mc.gui.screen()` 的控件树（type/x/y/w/h/label/active/hovered） |
| POST | `/ui/click?x=&y=[&button=0]` | client_ui | 按**坐标**点（无 selector/索引）；内部补调 `AbstractWidget.onClick`，详见 `references/ui-and-screenshots.md` |
| POST | `/ui/hover?x=&y=` | client_ui | 悬停（`Screen.mouseMoved`） |
| POST | `/ui/hover_slot?x=&y=` | client_ui | 悬停背包槽；`hoveredSlot` 永远报不出第 N 格（protected 无 accessor） |
| POST | `/ui/close` | client_ui | 关界面 |
| GET | `/screenshot` | client_ui | 截图（**异步落盘**，读前确认文件非空） |
| GET | `/player/inventory` | inventory | 背包内容 |
| GET | `/item/mainhand` | inventory | 主手物品 |
| POST | `/command` body `{cmd}` | command | 在客户端执行命令（多为 `/` 前缀的客户端命令） |
| POST | `/player/use` | player_action | 右键使用 |
| POST | `/player/use_item` | player_action | 使用手持物品 |
| POST | `/player/break` | player_action | 破坏准星方块（受命中校验约束，见下） |
| POST | `/player/mine` | player_action | 持续挖掘直到破坏 |
| GET | `/entity/list` | entity | 客户端可见实体 |
| POST | `/chat/send` | chat | 发聊天（注意服务端 20 秒限速会静默丢） |
| GET | `/chat/tail?n=` | chat_read | 读聊天栏（`data.text`）与动作栏（`data.actionbar`） |
| GET | `/block/sign?...` | block_extra | 读客户端侧告示牌 |
| POST | `/block/sign/edit` | block_extra | 编辑告示牌（需距离/未蜡封，且要等一 tick） |
| POST | `/block/command-block?...` | block_extra | 操作命令方块 UI |
| POST | `/quit` | core | 退出客户端 |
| POST | `/action/<name>` | action | 客户端动作后端 |

单人世界的三件必知（都踩过）：

- **打开「已有」存档会先弹 `BackupConfirmScreen`**（新建世界不弹）。`backup=ask`（默认）**什么都不点**，
  `skip` 跳过备份直接进，`do` 真备份一次；`backupAction` 回报实际结果（`ask` / `no-screen` / `skipped` /
  `backed-up` / `still-blocked`）。默认不替使用者做选择是刻意的。
- 创建 / 打开是**异步**的：`200` 只代表已提交，就绪判据是 `/status` 的 `inWorld`。
- 已在世界里再调创建 → `409`；`mode` / `gamemode` / `seed` 非法 → `400`。

客户端动作后端（`ClientActionBackend.actions()`，12 个）：
`move` `mine` `place` `use` `attack` `pick` `inventory` `gamemode` `ability` `spectate` `respawn` `sleep`

常用 body 形状（其余见 `ClientActionBackend.java` 的 `switch (action)` 分派）：

```json
{"mode": "walk", "durationMs": 400}          // /action/move
{"mode": "creative"}                          // /action/gamemode（严格枚举，未知值 400）
{"op": "set-selected", "slot": 0}             // /action/inventory 的 op 之一
```

## 判定时要记住的三句

- 客户端动作的真实性由**服务端**证明：客户端说「挖了」，要拿服务端 `/world/block` 回读才算数。
- 打 `/action/*` 前先看 `/status.data.actions` —— 两家后端动作集合不同，别假设对侧有同名动作。
- **「提示（不参与判定）」不是判据**：白名单、停在界面层、两端加载器不一致、`backup=ask` 这些都由 `devrun`
  以提示形式给出；判绿判红只看场景步骤的 `expect`（`results.json` 的 `nextStep` 还带 `affectsVerdict: false`）。
