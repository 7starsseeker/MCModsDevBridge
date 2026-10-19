# P4 主体记录：`/action/*` 动作层（2026-10-19 19:40 → 21:4x）

> 这一份只记 **P4 主体第一段（客户端动作层）**：12 个语义端点、`ClientActionBackend`、
> 三段式前置校验，以及每条端点的实测证据。服务端 `ServerActionBackend` 与 FakePlayer 仍未开工。
>
> 前置：`p4s0-scaffolding-removal-record.md`（拆脚手架）→ 本文件 → 下一步见 `STATE.md`。

## 0. 一句话结论

**12 条 `/action/*` 全部在真客户端上跑通**（Fabric 客户端 + 本地 Fabric 专用服，26.3），
每条都有「客户端返回 + 服务端独立核实」两份证据。三份骨架仍 `clean build` **零警告**。

同时定下三条硬事实（都是协议/环境层面，不是实现偷懒）：

1. `move` 的远端瞬移受**世界 gamerule `player_movement_check`（默认 true）**限制，不是协议不存在：
   关掉它之后客户端 60 格一次到位（实测，见 §10）。另有一条独立规则：服务端每 tick 只接受一个
   带位置的位置包，手搓第二个会被 `ServerGamePacketListenerImpl` 判
   `multiplayer.disconnect.invalid_player_movement` **直接断线** —— 所以端点仍然只改本地位置，
   位置包交给 `LocalPlayer.tick()` 自己发。
2. `SetCreativeModeSlotPacket` 在这套 26.3 环境里**无论走官方入口还是直接发包都被服务端丢弃**，
   `creative-set` 因此改成**响亮失败**（`501 TRAIT_UNAVAILABLE` + 证据），取物品走 `/command give`。
3. `PlayerAbilitiesPacket` 必须有 `mayfly`，否则客户端每 tick 被拉回地面；`PERFORM_RESPAWN` 会
   **替换整个 `LocalPlayer` 实例**，读回必须重新取 `player()`。

## 1. 文件清单（全部新增，共享层零 `net.minecraft` 泄漏到 main）

| 路径 | 行数 | 说明 |
|---|---:|---|
| `lab/shared/src/main/java/com/mcdev/probe/action/ActionBackend.java` | 35 | 接口：`name()` / `actions()` / `execute(ActionRequest)`，**不含任何 MC 类型**（共享 main 层侧两侧共用） |
| `lab/shared/src/main/java/com/mcdev/probe/action/ActionError.java` | 39 | 8 个错误码 + `httpStatus()` |
| `lab/shared/src/main/java/com/mcdev/probe/action/ActionException.java` | 85 | 受检异常，带 `code` 与结构化 `detail` |
| `lab/shared/src/main/java/com/mcdev/probe/action/ActionRequest.java` | 212 | JSON 请求只读视图（`blockPos` / `vec3` / `yawPitch` 同时吃 `{x,y,z}` 与 `[x,y,z]`） |
| `lab/shared/src/client/java/com/mcdev/probe/action/ClientActionBackend.java` | **1032** | 12 个动作的实现主体 |
| `lab/shared/src/client/java/com/mcdev/probe/action/ScriptedInput.java` | 74 | `extends ClientInput`，驱动真实走路 |

`ClientProbeServer.java` 只加了三处：`ACTION_BACKEND` 字段、`for (String action : ACTION_BACKEND.actions()) route("/action/" + action, ...)`、
`handleAction(...)`；`/status` 增 `actionBackend` 与 `actions` 两个键（能力仍由 `route()` 派生，不写第二份清单）。

## 2. 设计要点

### 2.1 错误码（把静默失败变响亮失败）

| code | HTTP | 何时 |
|---|---:|---|
| `BAD_REQUEST` | 400 | 字段缺失/类型错/前置不成立（如「玩家没死」） |
| `GAMEMODE_MISMATCH` | 400 | 三段式第二段：`detail{current, allowed}` |
| `PLAYER_NOT_FOUND` | 404 | 未连服务器 / 不在世界 |
| `TARGET_NOT_FOUND` | 404 | `detail` 带目标现状（`{block:"minecraft:air"}`、`{entityId}`） |
| `TRAIT_UNAVAILABLE` | 501 | 客户端不支持该能力（含 `creative-set` 被服务端丢弃） |
| `FROZEN` | 409 | tick 冻结期间不可执行 |
| `TIMEOUT` | 504 | 长动作未在预算内完成（挖掘），`detail{pos,timeoutMs}` |
| `INTERNAL` | 500 | 未预期异常，`detail.stack` 截 8 帧 |

### 2.2 线程模型（关键，写错必死锁）

所有 MC 对象访问走 `onClient(Callable)`（`CompletableFuture` + `minecraft.execute`，10s 超时），
**长动作每步只包一次 `onClient`，中间在 HTTP 线程 `sleep(50ms)` 让游戏继续 tick**。
`/action/move` 的 600ms 走路、`/action/mine` 的循环重击都靠这条；把整段塞进 `onClient` 会锁住游戏线程。

### 2.3 三段式校验（架构 §8 的落地）

`trait/连接可操作?` → `GameType 符合?`（`requireMode(...)`）→ `目标存在?`（后端内部）。
**校验与实现写在一起**，避免两处漂移；每条端点的 `packets` 字段由静态 `COVERAGE` 表派生，不手写第二份。

## 3. 逐端点实测（每条 = 客户端返回 + 服务端独立核实）

服务端核实手段：`/world/block`、`/command data get entity`、服务端 `/log/tail`。

| # | 端点 | 请求 | 客户端结果 | 服务端核实 |
|---|---|---|---|---|
| 1 | `move` | `{"mode":"walk"}` ×3 | 600ms 前进 **2.547 格**（≈4.25 格/s = 步行速度），`yaw=90` 时向 −x 走 2.665 格；`steps:8` | 位置包由 LocalPlayer 自己发，无断线 |
| 1b | `move` | `{"mode":"teleport","to":[9.5,72,-78]}`（1 格内） | `serverAccepted:0`（同一 tick 位置未发） | 位置变化一致 |
| 1c | `move` | `{"mode":"teleport","to":[9.5,73,-78]}`（1.3 格） | `applied:false` + `serverAccepted:1` + `corrected:true` + `hint` | 服务端日志只发一次纠偏、**不断线** |
| 2 | `mine` | `{"pos":[11,72,-79],"face":"east"}`（石） | `broken:true, ticks:2, mode:creative` | `/world/block 11,72,-79` → `minecraft:air` ✅ |
| 3 | `place` | `{"pos":[13,72,-79],"face":"up"}` | `result:"Success"` | `/world/block 13,72,-79` → `minecraft:stone`，`/item/mainhand` stone 61 ✅ |
| 4 | `use`(block) | `{"target":"block","pos":[8,72,-79]}` | `result:"Success"`（石头不可交互，但包路径通） | 无副作用符合预期 |
| 4b | `use`(entity) | 僵尸 id `3509` | `result:"Pass[]"`、`held:apple×3` | 苹果不能喂僵尸，无副作用 ✅ |
| 4c | `use`(item) | 手持苹果 | `result:"Success"`（吃掉） | 客户端回显 |
| 5 | `attack` | 僵尸 id `3509` | `healthBefore:19.0, healthAfter:18.8077, damaged:true` | 实体血量下降 ✅ |
| 6 | `pick` | `{"pos":[11,72,-76]}`（钻石块） | `result:"Success"`, `held:*diamond_block` | `/item/mainhand` diamond_block ✅ |
| 7 | `inventory` | `set-selected slot 36` | `selectedSlot:0` | 主手换成 hotbar 0 ✅ |
| 7b | `inventory` | `click slot 36 button 0 pickup` | `containerId:0, slot:36, input:PICKUP` | 无报错 |
| 7c | `inventory` | `button buttonId 0` | `containerId:0` | 无报错 |
| 7d | `inventory` | `drop all:false`（创造） | `dropped:{air,0}` | 空手丢弃无副作用 ✅ |
| 7e | `inventory` | `close` | `menu:"InventoryMenu"` | 无报错 |
| 7f | `inventory` | `creative-set slot 36 stone 8` | **501** + `detail{applied:apple×3, directPacket:true, infiniteMaterials:true}` | 见 §4.2 |
| 8 | `gamemode` | `{"mode":"creative"}` | `before:survival, after:creative, applied:true` | `/command data get entity … playerGameType` → **1** ✅ |
| 8b | `gamemode` | `{"mode":"spectator"}` | `after:spectator, applied:true` | 服务端确认 ✅ |
| 9 | `ability` | `{"flying":true}` | `flyingBefore:false, flyingAfter:true, mayfly:true` | 服务端 abilities NBT 里 `flying:1b` |
| 9b | `ability` | `{"flying":false}` | `flyingAfter:false` | 落地正常 |
| 10 | `spectate` | 僵尸 id | `cameraEntity:"3509"` | 视角切换（客户端读回） |
| 11 | `respawn` | 未死时 | **400** `BAD_REQUEST`「player is not dead」 | 无副作用 |
| 11b | `respawn` | `/command kill` 之后 | `alive:true, health:20.0, position:(11.5,72,-74.5)` | 服务端重生到出生点 ✅ |
| 12 | `sleep` | 床 `[10,72,-74]` foot/facing=north + `time set midnight` | `result:"Success"`, `sleeping:true` | 玩家 y=**72.5625**（床高）✅ |

## 4. 五个坑（都是实测出来的，不是推测）

### 4.1 瞬移的开关是 gamerule `player_movement_check`（用户指出，实测确认）

第一版手搓 `ServerboundMovePlayerPacket$PosRot` 后服务端日志：

```
Player211 moved too quickly! 107.1,6.0,166.7
Player211 lost connection: Invalid move player packet received
```

字节码定位 `ServerGamePacketListenerImpl`：`if (packet.hasPosition() && this.receivedPositionThisTick) disconnect(multiplayer.disconnect.invalid_player_movement)`
—— **每 tick 只接受一个带位置的位置包**，手搓的那个和客户端自己发的撞车 ⇒ 不是踢回，是断线。
修法：`teleport` 分支不发位置包，只 `absSnapTo(...)+setDeltaMovement(ZERO)`，位置包交给 `LocalPlayer` 自己的 tick。

第二层才是「瞬移被拒」的真原因：`handleMovePlayer` 里的
`shouldCheckPlayerMovement(...)` 由 `GameRules.PLAYER_MOVEMENT_CHECK`（gamerule 名
**`player_movement_check`**，26.x 改成下划线风格；camelCase 会报 `Incorrect argument`）把关。
实测（§10）：默认 `true` 时 60 格瞬移被 `"moved too quickly"` 拒绝并回一个纠偏包；设成
`false` 后同样 60 格一次到位、服务端位置与目标一致（水平误差 0.000，竖直 0.768 是落地重力）。
端点因此只**如实回报**（`serverAccepted/horizontalError/verticalDrift/snappedBack/hint`），
不去偷偷改世界的 gamerule。

### 4.2 `SetCreativeModeSlotPacket` 被丢弃

`MultiPlayerGameMode.handleCreativeModeItemAdd` 的守卫（`javap -c`）：`player.hasInfiniteMaterials()`
且 `connection.isFeatureEnabled(stack.getItem().requiredFeatures())`；两者都真才发包。
实测两者都为真，**直接 `connection.send(new ServerboundSetCreativeModeSlotPacket(slot, stack))` 也一样被丢**
（服务端 `handleSetCreativeModeSlot` 的 `hasInfiniteMaterials`/`item.isItemEnabled` 都通过，落点正确）。
同一环境下 `/command give` 正常落进槽位（`containerSlot` 核对过）。
⇒ 结论：这是**这套 26.3 环境的行为**，不是参数写错；端点保持 501 + 证据 + `hint`，取物品走 `give`。

### 4.3 `sleep` 的前置比你想到的多

`useItemOn` 床返回 `Success[swingSource=SERVER_ONLY]` 但玩家没躺下，原因依次是：
床的 `part=head` 必须在 foot 的 `facing` 方向（放反了服务端静默 `Could not set the block`）、
必须是夜晚（`time set midnight`）、附近不能有怪（`kill @e[type=zombie]`）。
现在 `sleep` 在 `sleeping:false` 时返回 `hint` 列出这三条，并把创意模式也放行（创造也能睡床）。

### 4.4 `respawn` 会替换 `LocalPlayer` 实例

`ClientPacketListener.handleRespawn` 之后 `mc.player` 是新对象，旧引用永远 `isDeadOrDying()==true`。
第一版就是这样「重生成功但报 alive:false」。现在读回重新取 `player()`，并写进响应 `note`。

### 4.5 玩家名每次启动都变

`Player15 / Player118 / Player211 / Player261 / Player518 / Player765` 随机。测试脚本必须先读
`/status.player` 再 `/command op <name>`；写死名字会得到 `applied:false` + note（这条结构化失败本身是对的）。

### 4.6 挖掘要先「对准」

服务端按 `player.getViewVector()` 算朝向，玩家原地不动去挖 10 格外的方块命中的是空气（`hitType:MISS`），
早期表现为「`/action/mine` 返回 ok 但方块还在」。现在 `mine` 先 `aimAt(pos)`（算 yaw/pitch + 发
`MovePlayerPacket$Rot`，只旋转不发位置，不触发 §4.1）再开始；实测 `ticks:2` 破石。
另外服务端对超距破坏直接忽略（`Player118` 距目标 19.5 格时 `Set the block` 日志都不出现）——测试要先 `tp` 到目标旁。

## 5. 包覆盖面（诚实口径）

| 已覆盖的 serverbound 包（21 / 58 ≈ 36%） | 端点 |
|---|---|
| `MovePlayerPacket`(Pos/PosRot/Rot/StatusOnly)、`PlayerInputPacket`、`PlayerCommandPacket`(sprint) | move |
| `PlayerActionPacket`(START/CHANGE_DESTROY_DIRECTION/STOP/ABORT) | mine |
| `UseItemOnPacket` | place / use(block) / sleep |
| `UseItemPacket`、`PlayerActionPacket(RELEASE_USE_ITEM/STAB)` | use(item) / spectate |
| `AttackPacket`、`PunchPacket`、`InteractPacket` | attack / use(entity) |
| `PickItemFromBlockPacket`、`PickItemFromEntityPacket` | pick |
| `ContainerClickPacket`、`ContainerClosePacket`、`ContainerButtonClickPacket`、`SetCarriedItemPacket`、`SetCreativeModeSlotPacket`(发得出、服务端丢)、`PlayerActionPacket(DROP_ITEM/DROP_ALL_ITEMS)` | inventory |
| `ChangeGameModePacket` | gamemode |
| `PlayerAbilitiesPacket` | ability |
| `SpectatorActionPacket`、`TeleportToEntityPacket` | spectate |
| `ClientCommandPacket(PERFORM_RESPAWN)` | respawn |
| `ChatCommandPacket` | `/command`（P0 起就有） |

**未覆盖 37 个**，且都不在这 12 个语义端点的语义里：特殊方块 UI 7（命令方块/结构方块/告示牌…）、
查询与管理 12（`ClientTickEnd`/`ChangeDifficulty`/`SetGameRule`/`EntityTagQuery`…）、
聊天 5（`Chat`/`ChatAck`/`ChatSessionUpdate`/`Signed`/`CommandSuggestion`）、容器剩余 8（`PlaceRecipe`/
`RenameItem`/`SelectTrade`/`SetBeacon`/`EditBook`/`SetJigsaw`…）、移动/骑乘剩余 4（`MoveVehicle`/
`PaddleBoat`/`AcceptTeleportation`/`MovePlayerPacket$StatusOnly` 命中但未单列）。

⇒ **架构 §8 验收标准里的「58 包覆盖 ≥45」在本段没有达成**（12 语义端点最多摊到 21 个包）。
要凑到 45 必须新增语义端点（容器配方/交易、聊天、命令方块类 UI、游戏规则/难度），
这是 P4 第二段（服务端 + 容器 + 骑乘）的工作量，**我没有把它们塞进这一轮**，理由见 §7 决策 6。

## 6. 构建与产物（零警告证据）

| 平台 | 命令 | 结果 | jar |
|---|---|---|---|
| Fabric | `gradlew --no-daemon --console=plain clean build` | `BUILD SUCCESSFUL in 9s`，日志 `tmp/build-fabric-p4action.log` 中 `警告/错误:` **0 处** | `mcdev_probe-1.0.0.jar` **75,364 B**（+sources 39,631 B） |
| Forge | 同上 | `BUILD SUCCESSFUL in 9s`（`tmp/build-forge-p4action.log`） | **32,739 B** |
| NeoForge | 同上 | `BUILD SUCCESSFUL in 11s`（`tmp/build-neoforge-p4action.log`） | **36,295 B** |

客户端 `/status`（实测）：

```json
{"side":"client","loader":"fabric",
 "capabilities":["core","net","client_ui","inventory","command","player_action","entity","action"],
 "actionBackend":"client",
 "actions":["move","mine","place","use","attack","pick","inventory","gamemode","ability","spectate","respawn","sleep"],
 "player":"Player15","playerPos":"(10.5, 72.0, -77.5)","playerRot":"89.99999,0.0","gamemode":"CREATIVE"}
```

## 7. 本段的决策（供用户复核）

1. **动作层走 vanilla 客户端自身的入口**（`MultiPlayerGameMode` + `LocalPlayer`），不手搓 58 个包 ——
   `move` 的位移、`mine` 的破坏循环、`attack` 的伤害判定都由原版逻辑发出，包只是副产品；
   只有它没入口的（`ChangeGameMode`/`PlayerAbilities`/`ClientCommand`）才显式发包。
   理由：手搓包既容易与服务端每 tick 规则冲突（§4.1），又绕过了原版的前置校验。
2. **`move` 默认不动世界设置**：默认 gamerule 下 ≤1 格走本地修正，更远返回结构化 correction，`hint`
   直接点名开关 `POST /command {"cmd":"gamerule player_movement_check false"}`（或服务端 `/command tp`）。
   关 gamerule 是**测试环境的选择**，留给调用方决定，端点不自己改。
3. **`creative-set` 失败即报 501，不兜底**：不偷偷改用 `/command give`（那是另一种语义、会掩盖真实问题），
   只在 `hint` 里指路。取物品的测试示例继续用 `give`。
4. **新增 `/entity/list`**（capability `entity`）：`attack`/`spectate` 只吃数值 entityId，客户端没有别的发现手段。
   范围参数 `r`（默认 32），跳过自己，输出 `id/type/uuid/distance/pos`。
5. **`sleep` 放行创意模式**（创造玩家在原版也能上床），并要求 `bedPos` 处真是 `BedBlock`。
6. **没有为了凑 45/58 包覆盖率新增语义端点**：本轮目标是 12 条语义端点可用；容器配方/交易/聊天/
   命令方块 UI 需要新的端点设计（会改 HTTP 契约），属下一段。宁可如实报 21/58，不虚报。
7. **`GAMEMODE_MISMATCH` 用 400 而不是 409**：它是请求非法（前置不成立），不是状态冲突；
   409 留给 `FROZEN`。若用户希望与 HTTP 语义更严格对齐，改一处 `ActionError.httpStatus()` 即可。

## 8. 未做 / 下一步

- `ServerActionBackend`（服务端最小集 + 可选 FakePlayer）—— 未开工。
- `move` 的远端瞬移已确认可做（§10），当前形态是「调用方自己关 `player_movement_check` 或走
  服务端 `/command tp`」。是否再封一个 `mode:"server-teleport"`（客户端探测 → 服务端代劳）需要用户拍板，
  那会引入跨端编排，属架构 §10.3 六步编排的范围。
- `creative-set` 的环境原因未定位到根因（服务端 handler 的守卫都通过），若要继续查需要抓服务端
  `handleSetCreativeModeSlot` 的完整调用日志。
- `/ui/*` 的 `hover_slot`（`AbstractContainerScreen.hoveredSlot` protected 无 accessor）仍未解。

---

## 9. 补跑：`/player/state` + 可重跑回归套件（本段末尾，**本节数字取代 §6**）

### 9.1 新增服务端路由 `/player/state`（capability `player`）

为什么非加不可：`/command` 的 `result` 字段**不是成功布尔**。实测 `data get entity <n> playerGameType`
恒返回 `0`（命令返回值不是该字段，真正的值只出现在聊天行里），拿它断言会得到假 FAIL。
所以补一条**直接读 `ServerPlayer` 字段**的旁证路由 —— 这也正是「服务端是观察者」这条定位（架构 §8.2）。

`GET /player/state?player=<name>`（省略取第一个在线玩家）返回：
`name / online / gamemode / gamemodeId / creative / survival / adventure / spectator / health / maxHealth /
food / position / flying / invulnerable / onGround / sleeping / dead`。

不报 `mayfly`：NeoForge 的补丁把 `Abilities.mayfly` 标了 `@Deprecated`，而 shared main 层三家共用 ——
报它会让 NeoForge 构建出现唯一一条警告，破坏「零警告」验收项（这条警告就是本轮实际抓到并消掉的）。

### 9.2 回归套件 `tmp/p4a-action-suite.ps1`

14 个用例，自己布置环境（`op` / `gamemode` / `fill` 平台 / `setblock` 目标方块与床 / `summon` 僵尸 /
`tp` 到射程内），**断言全部走服务端**（`/world/block`、`/player/state`、`attack` 前后的服务端血量差），
只把客户端返回值当线索。修正三处「验证方法本身错」之后连续三次 **PASS=14 FAIL=0**，
其中最后一次是**部署最终 jar 之后**跑的。

| 用例 | 证据 |
|---|---|
| move(walk) | `distance=2.331 steps=6`（600ms） |
| move(teleport≤1) | `applied / corrected / serverAccepted=true` |
| mine | `serverBlock=minecraft:air` |
| place | `serverBlock=minecraft:stone`，`heldItemTransformedTo=64 minecraft:stone` |
| use(block) | `result=Success[...]` |
| use(entity) | `entityId=289 result=Pass[]` |
| attack | `20 -> 19.807775 damaged=True` |
| pick | `mainhand=minecraft:diamond_block` |
| inventory(set-selected) | `selectedSlot=0` |
| gamemode | `serverGameType=1`（走 `/player/state`） |
| ability(fly) | `flying false->true mayfly=True`，关掉后 `false` |
| spectate | `camera=entity.minecraft.zombie` |
| sleep | `sleeping=True serverSleeping=True` |
| respawn | 未死 `400 BAD_REQUEST`；kill 后 `200 alive=True health=20` |

### 9.3 四个「测试本身错」的教训（比代码 bug 更值得记）

1. **`/command` 的 `result` 不是成功布尔** —— 见 §9.1。判服务端状态一律走 `/player/state`。
2. **`/item/mainhand` 返回的 `data.item` 是字符串**（`"minecraft:diamond_block"`），不是 `{id:...}`。
   按 `data.item.id` 取会得到 null，表现成「pick 失败」。
3. **`sleep` 的站位是前置**：床在 >2 格时 26.3 报「床太远」并静默不睡；必须站在床脚旁一格内、
   且 `onGround` 为真（刚 `tp` 完要等落地）。断言要看 `/player/state.sleeping`，别只看客户端一次 `useItemOn`。
4. **`mayfly` 在 NeoForge 上是 `@Deprecated`** —— shared main 层不能报它，否则破坏零警告。

### 9.4 最终产物（三家全量 `clean build`，0 警告 0 错误）

| 平台 | 结果 | jar | 日志 |
|---|---|---|---|
| Fabric | `BUILD SUCCESSFUL in 7s` | `mcdev_probe-1.0.0.jar` **79,993 B** | `tmp/build-fabric-p4final.log` |
| Forge | `BUILD SUCCESSFUL in 9s` | **33,955 B** | `tmp/build-forge-p4final.log` |
| NeoForge | `BUILD SUCCESSFUL in 7s` | **37,511 B** | `tmp/build-neoforge-p4final.log` |

最终 fabric jar 已部署到 `<MC_ROOT>\Server\mods\` 并用它跑完套件（14/14）。

---

## 10. 追加验证：`player_movement_check`（用户指出的开关，实测确认）

用户指出「moved too quickly 可以在服务器设定里关掉」。下面是查证过程与实测，**取代 §4.1 第一段的结论口径**。

### 10.1 确定 gamerule 叫什么

26.x 把 gamerule 名改成了下划线风格，camelCase 一律 `Incorrect argument for command`：

| 猜测 | 服务端回显 |
|---|---|
| `gamerule playerMovementCheck` | `Incorrect argument for command` |
| `gamerule disablePlayerMovementCheck` | `Incorrect argument for command` |
| `gamerule elytraMovementCheck` | `Incorrect argument for command` |
| `gamerule player_movement_check` | `Game rule player_movement_check is currently set to true` ✅ |
| `gamerule minecraft:player_movement_check` | 同上（带不带命名空间都接受） |

名字来源：客户端 jar 的 `assets/minecraft/lang/en_us.json` 里有
`gamerule.minecraft.player_movement_check = Do player movement check`。

> 顺手再次踩到同一个坑：这三种错误写法 `/command` 都回 `result:0` —— **`result` 不是成功布尔**，
> 判断只能看服务端日志里的聊天回显（`tmp/g-ver-server.out`）。

### 10.2 实测：关掉之后 60 格一次到位

| gamerule | 请求 | 响应关键字段 | 服务端 `/player/state.position` |
|---|---|---|---|
| `true`（默认） | `move teleport to [37.5,72,-82.5]`（+30 格） | `serverAccepted:false, horizontalError:30.0, snappedBack:true` | `7.5,72.0,-82.5`（原地） |
| `false` | `move teleport to [67.5,72,-82.5]`（+60 格） | `serverAccepted:true, horizontalError:0.0, verticalDrift:0.768` | `67.5,68.65,-82.5`（到位，Y 是落地重力） |
| `true`（还原） | `move teleport to [7.5,72,-82.5]`（−60 格） | `serverAccepted:false, horizontalError:60.0, snappedBack:true` + hint 点名 gamerule | `67.5,63.0,-82.5`（原地） |

结论：**瞬移能力一直在，开关在世界设置里**，代码不需要为它做任何特判；端点只需要如实回报。

### 10.3 判据修正：3D 距离 → 水平误差

旧实现 `errorBlocks = position.distanceTo(target)` 会把「刚落地时因重力下坠 0.7 格」误判成
「被服务端拒绝」。现在拆成三个字段：

- `horizontalError`：水平差（判据，≤0.75 视为到位）
- `verticalDrift`：竖直漂移（只记录，不算失败）
- `snappedBack`：位置是否回到 `from` 附近（≤0.75）⇒ 这才是「服务端拒绝了」的证据

`hint` 也因此分成两条：被拉回 → 点名 `gamerule player_movement_check false` 或 `/command tp`；
没落在目标 → 提示检查区块是否加载、目标是否可站立。

### 10.4 套件偶发 FAIL 的根因（不是动作层）

现象：连续几轮里 `sleep` 时好时坏，失败时 `landed=True`、客户端与床距离 0.7 格、结果 `Success[SERVER_ONLY]`
但 `sleeping=False`；手动在同一坐标必成功。

根因：`kill @e[type=minecraft:zombie]` **只作用于已加载的实体**。套件在远处执行清怪时，测试区区块还没加载
⇒ 上一轮 `summon` 的僵尸留到玩家 `tp` 落地之后才出现 ⇒ 服务端以「附近有怪物」静默阻止入睡。

修法（`tmp/p4a-action-suite.ps1`）：顺序改成 `gamemode`/`tp` → 轮询 `/player/state.onGround`（最长 6 秒）
→ **再** `kill @e[type=minecraft:zombie]` → `time set midnight` → `sleep`，最多两次。

教训（与 §9.3 同类）：`/command` 回 `ok` 不等于副作用生效，凡是有「加载」「落地」「模式切换」前提的
操作，都要先等到可观测前提再动手。

### 10.5 `spectate` 补 `attached` / `note`

手动复现时碰到 `spectate` 回 200、`cameraEntity` 却是 `entity.minecraft.player`（没接上）的情况。
现在返回 `attached:true|false`，false 时附 `note`：服务端只在玩家真是旁观者时才接受
`SpectatorAction`（先看 `/player/state.gamemode`）。

### 10.6 产物与回归

| 平台 | 结果 | jar | 日志 |
|---|---|---|---|
| Fabric | `BUILD SUCCESSFUL in 11s` | `mcdev_probe-1.0.0.jar` **80,288 B** | `tmp/build-fabric-gamerule.log` |
| Forge | `BUILD SUCCESSFUL in 15s` | **33,955 B** | `tmp/build-forge-gamerule.log` |
| NeoForge | `BUILD SUCCESSFUL in 9s` | **37,511 B** | `tmp/build-neoforge-gamerule.log` |

警告计数均为 **0**。客户端重启后连跑两遍套件：`SUMMARY PASS=14 FAIL=0 total=14`（两次）。
三份 jar 已同步到 `<MC_ROOT>\Server{,1,2}\mods\`；`player_movement_check` 已还原为默认 `true`。

---

## 11. 追加：`mode:"server-teleport"`（跨端编排的第一步）

### 11.1 为什么加这条模式

§10 的结论是「客户端瞬移受世界 gamerule 限制」。用户（m01211）批准把「服务端代劳」做成一等模式：
*这种辅助MOD开发工具，在相关的测试环境中肯定是能做的事情越多越好的*。

`POST /action/move` 的 `mode` 因此变成三态：

| 模式 | 谁执行位移 | 受 `player_movement_check` 限制 | 需要的环境 |
|---|---|---|---|
| `teleport` | 客户端改本地坐标 | **是**（远端必被拉回） | 只要有客户端 |
| `walk` | 客户端输入驱动（`ScriptedInput`） | 否（全程在检查范围内） | 只要有客户端 |
| `server-teleport` | **服务端探针执行 `/tp`** | 否（`/tp` 直接 `setPos`） | 客户端 + 服务端探针可达 |

配置面只有两个 JVM 属性，都有默认值：

- `-Dmcdev.probe.server.url`（默认 `http://127.0.0.1:39001`）——客户端到服务端探针的基址；
  实测用 `39011`（Fabric 测试服）。`/status` 新增 `serverProbeUrl` 字段报出当前值。
- `-Dmcdev.probe.token`（默认 `mcdev`）——沿用同一条鉴权头。

### 11.2 链路与失败语义

```
client /action/move{mode:server-teleport,to} 
  → POST server/command {"cmd":"tp <name> x y z"}      // 服务端代劳
  → GET  server/player/state?player=<name>             // 读回权威坐标
  → {applied, horizontalError, serverPosition, serverProbe{url,command,commandOk}}
```

- 服务端探针**不可达** → `501 TRAIT_UNAVAILABLE`（不是 500）：客户端没坏，缺的是这次测试没把服务端接上；
  `detail` 带 `url` 与 `cause`，message 直接告诉人该传哪个 `-D`。
- `/command` 被拒（`ok:false`）→ `500 INTERNAL` + `detail.serverResponse` 原样带回。
- 玩家名不在服务端 → `404 PLAYER_NOT_FOUND`。
- 抛错路径全部是**响亮失败**，没有「回了 200 但人没动」这一格。

判据仍用水平误差（§10.3）：`/tp` 到空中后玩家会按重力下坠，`serverPosition.y` 比目标低是正常现象
（实测 100.0 → 97.89 → 94.31）。

### 11.3 两个 HTTP 坑：都是 5 秒超时，都不是网络问题

| 现象 | 真因 | 修法 |
|---|---|---|
| 客户端调服务端探针必超时（`HttpTimeoutException: request timed out`），PowerShell 打同一 URL 却 252ms 成功 | 本机为 gradle 设了 `http.proxyHost=127.0.0.1:<PROXY_PORT>`，JDK `HttpClient` 默认跟随 → 请求被代理吞掉 | 构建时 `.proxy(HttpClient.Builder.NO_PROXY)`（探针之间永远是回环/LAN） |
| 同样是超时，`NO_PROXY` 之后仍然超时；写一个 30 行的独立 Java 程序打同一 URL 也超时 | JDK `HttpClient` 默认 HTTP/2，对明文先发 h2c `Upgrade`；探针用的 `com.sun.net.httpserver` 既不认也不明确拒绝，客户端就一直等 | `.version(HttpClient.Version.HTTP_1_1)` —— 加上这一行，同一个独立程序从「必超时」变成 **49 ms** |

第二条值得记住的原因：**它看起来像网络/防火墙问题，实际是协议协商**。判别手段是「换非 Java 客户端能通吗」——
PowerShell 通、独立 Java 程序不通、且把版本钉到 1.1 就通，三条合起来才能定案。

### 11.4 实测（同一次会话，同一玩家，相隔 1 秒）

| 步骤 | 结果 |
|---|---|
| A `mode=teleport` → `[60,100,-74]`（56 格外） | `serverAccepted:false, horizontalError:67.041, snappedBack:true`；服务端日志 `Player137 moved too quickly! 55.96535749314929,35.0,0.8556634580618407` |
| B `mode=server-teleport` → 同一目标 | `applied:true, horizontalError:0.0`，`command:"tp Player943 60.000 100.000 -74.000"`，`commandOk:true`，**整条链路 708 ms** |
| C 服务端 `/player/state` 复核 | `position: 60.0,94.31161120717262,-74.0`（正在下坠，水平已到位） |

回归套件加第 15 条 `move(server-teleport)`：瞬移到 56 格外 → 断言 `applied` **且**用 `/player/state` 复核
（不只看客户端自报），再 `tp` 回测试区继续后面的用例。

**`SUMMARY PASS=15 FAIL=0 total=15`**（`tmp/p4a-suite-result.log`），其中新增一行
`move(server-teleport) PASS [200] applied=True horizontalError=0 serverPos=60.0,95.15552175294312,-74.0`。

### 11.5 产物

| 平台 | 结果 | jar | 日志 |
|---|---|---|---|
| Fabric | `BUILD SUCCESSFUL in 9s` | **83,903 B** | `tmp/build-fabric-servertp.log` |
| Forge | `BUILD SUCCESSFUL in 6s` | 33,955 B（未变） | `tmp/build-forge-servertp.log` |
| NeoForge | `BUILD SUCCESSFUL in 8s` | 37,511 B（未变） | `tmp/build-neoforge-servertp.log` |

三家警告计数 **0**；三份 jar 已同步到 `<MC_ROOT>\Server{,1,2}\mods\`。新服务端 jar 起服后
15 路由矩阵仍是 `200=12 501=3 other=0`（服务端侧没被这次改动影响）。

---

## 12. 追加：`ServerActionBackend`（第二个后端）+ 一个被 javap 揭穿的静默失败

### 12.1 定位：服务端只做「它天然权威」的四件事

架构 §8.2 的结论没变 —— 角色操作（挖/放/用/攻击/视角/走路）归客户端，因为只有客户端有输入与预测。
服务端能做、且**只有它做才算数**的是四件改世界状态的事：

| 动作 | 为什么必须在服务端 |
|---|---|
| `teleport` | 客户端瞬移要过 `player_movement_check` 校验（§4.1 / §10），服务端写位置没有这个问题 |
| `gamemode` | `ServerPlayer.setGameMode` 是 `/gamemode` 命令的落点，客户端只能「请求」 |
| `ability` | `Abilities` 是服务端实体字段，客户端改的是本地副本 |
| `inventory` | 容器交互的权威是 `AbstractContainerMenu`（架构 §8.3：一次覆盖 29 个 `*Menu`） |

路由与客户端**同名同信封**：`POST /action/<name>`，`{ok:true,data}` / `{ok:false,error:{code,message,detail?}}`，
只是 `data.backend` 是 `"server"`。`/status` 增加两个字段：`actionBackend`（`"client"` / `"server"` / 缺省）
与 `actions`（本端支持的动作名数组）—— 调用方按这两个字段选端，不需要知道哪端有实现。

### 12.2 四个动作的落点（`lab/shared/src/main/java/com/mcdev/probe/action/ServerActionBackend.java`）

| 动作 | 实现 | 响应关键字段 |
|---|---|---|
| `teleport {player,to:[x,y,z],look?}` | `ServerPlayer.teleportTo(x,y,z)`（差量过大时走 `ClientboundPlayerPositionPacket`） | `before` / `after` / `horizontalError` / `applied` |
| `gamemode {player,mode}` | `ServerPlayer.setGameMode(GameType)`（广播 `ClientboundGameEventPacket.CHANGE_GAME_MODE`） | `before` / `after` / `applied` |
| `ability {player,flying?,invulnerable?}` | 改 `player.getAbilities()` 字段 + `onUpdateAbilities()`（`flying=true` 时隐式给 `mayfly`，否则服务端每 tick 拉回地面） | `flyingBefore/After` / `invulnerableBefore/After` |
| `inventory {player,op}` | `give`（`setByPlayer` / `getInventory().setItem`）、`clear`、`set-selected`（+`ClientboundSetHeldSlotPacket`）、`click`（`menu.clicked(slot,button,ContainerInput,player)` + `broadcastChanges()`）、`slots`（读 `InventoryMenu` 46 槽） | `added` / `selected` / `carried` / `items[]` |

前置校验与客户端一致：玩家不在线 → `404 PLAYER_NOT_FOUND`（`player not online: <name>`），
不认识的字段/动作 → `400 BAD_REQUEST`，`slots` 越界 → `404 TARGET_NOT_FOUND`。

### 12.3 一个被 javap 揭穿的静默失败：`GameType.byName`

首轮服务端实测里 `{"mode":"nonsense"}` 回了 **200**，并且真把玩家切成了生存模式。
`javap -c` 给出原因：

```
public static GameType byName(String s)  →  byName(s, SURVIVAL)      // 单参就是「两参 + SURVIVAL 兜底」
public static GameType byName(String s, GameType fallback)
    → CODEC.byName(s); if (null) return fallback; else return result
```

即**未知输入静默返回 `SURVIVAL`**。这正是本项目要消灭的那类失败，所以新增
`lab/shared/src/main/java/com/mcdev/probe/action/GameTypes.java`：显式 `switch` 四选一，未知输入抛
`BAD_REQUEST`。两个后端都改成调它（客户端原第 742-746 行、服务端原第 134-137 行）。

修前 / 修后（同一台服、同一副请求）：

| 版本 | `POST /action/gamemode {"mode":"nonsense"}` |
|---|---|
| 旧 | `[200] {"requested":"survival","before":"survival","after":"survival","applied":true}` |
| 新 | `[400] {"code":"BAD_REQUEST","message":"unknown gamemode 'nonsense' (expected survival|creative|adventure|spectator)"}` |

### 12.4 三家实测（同一份共享实现，三个加载器）

| 平台 | `/status` | 15 路由矩阵 | 严格解析 | 无玩家时 loud fail |
|---|---|---|---|---|
| Fabric 39011 | `loader=fabric, actionBackend=server, actions=[teleport,gamemode,ability,inventory]` | `200=12 501=3` | `400 BAD_REQUEST` | `404 PLAYER_NOT_FOUND` |
| Forge 39012 | `loader=forge, actionBackend=server, actions=[…同上]` | `200=12 501=3` | `400 BAD_REQUEST` | `404 PLAYER_NOT_FOUND` |
| NeoForge 39013 | `loader=neoforge, actionBackend=server, actions=[…同上]` | `200=12 501=3` | `400 BAD_REQUEST` | `404 PLAYER_NOT_FOUND` |

回归套件从 15 例扩到 **19 例**，新增四例（`tmp/p4a-action-suite.ps1` §3）：

| 用例 | 断言 | 实测证据 |
|---|---|---|
| `server/status` | `actionBackend=server` 且四项动作齐 | `backend=server actions=teleport,gamemode,ability,inventory` |
| `server/inventory(give+slots)` | `give` 成功且 `slots` 里能看到 | `added=True menu=InventoryMenu slots=46 breadStacks=1` |
| `server/teleport` | `applied` 且 `/player/state` 复核 | `applied=True horizontalError=0 statePos=30.0,71.23,-80.0` |
| `server/gamemode(strict)` | 未知模式必须 400 | `[400] unknown gamemode 'nonsense'` |

**`SUMMARY PASS=19 FAIL=0 total=19`**（`tmp/p4a-suite-result.log`）。

### 12.5 两个工具链坑（都不是产品代码的问题，但会骗人）

| 现象 | 真因 | 修法 |
|---|---|---|
| `taskkill` 杀不掉客户端，杀完再起就有第二个客户端抢同一个探针端口，`/action/gamemode` 返回的还是旧行为（`requested:"survival"`） | `tmp/p4a-start-client.ps1` 往 pid 文件写的是 **pwsh wrapper** 的 pid，不是 runClient 那个 JVM | READY 后用 `CommandLine -like '*fabric.dli.env=client*'` 回填真正的 java pid；同时先删旧日志再轮询 READY（否则读到上一轮同一行 →「READY after 0s」） |
| Forge `clean build` 一次跑完后 jar 只有 **12,791 B / 3 个类**，`compileJava`、`jar`、`BUILD SUCCESSFUL` 全绿 | `clean` 与编译在同一个 invocation 里、又开着 `configuration-cache`，增量编译只产出「本轮改过的类」 | 用 `--rerun-tasks`（实测恢复 45,925 B / 15 类）；每次构建后**核对类数**：fabric 19 / forge 15 / neoforge 17 |

第二条尤其危险：产物缺失不会报错，只会表现为运行期 `NoClassDefFoundError` 或者「模块没加载」。
每次构建后核对 jar 内容（`tar -tf` 数 `.class`）比读构建结论可靠。

### 12.6 产物

| 平台 | 结果 | jar | 类数 | 日志 |
|---|---|---|---|---|
| Fabric | `BUILD SUCCESSFUL in 9s` | **95,882 B** | 19 | `tmp/build-fabric-serverbackend.log` |
| Forge | `BUILD SUCCESSFUL in 10s` | **45,925 B** | 15 | `tmp/build-forge-serverbackend-rerun.log` |
| NeoForge | `BUILD SUCCESSFUL in 12s` | **49,481 B** | 17 | `tmp/build-neoforge-serverbackend.log` |

三家 `警告 / 错误` 计数 **0**；三份 jar 已同步到 `<MC_ROOT>\Server{,1,2}\mods\` 并按上面 §12.4 复验。



## 13. 追加：服务端补 OP、纯服务端代跑动作、两个可重跑套件

用户第四批指令（原话）：*"服务端补相关OP；接下来根据之前安排的P的顺序来做；纯服务端跑动作做一下"*。
本节记录这一批的落点、实测证据，以及四个「验证方法自身错」的新教训。

### 13.1 服务端从 4 个动作扩到 9 个

`ServerActionBackend` 现在和客户端**同名同形**地提供九个端点（`/status.actions` 两家一致）：

| 动作 | 服务端承担的做法 | 为什么在服务端做 |
|---|---|---|
| `teleport` | `ServerPlayer.teleportTo(x,y,z)` | 服务端是唯一权威，落点就是真落点 |
| `gamemode` | `ServerPlayer.setGameMode(GameType)` | 结果只由服务端决定 |
| `ability` | 改 `Abilities` + `onUpdateAbilities()` | 同上；飞行还必须给 `mayfly` |
| `mine` | `ServerPlayerGameMode.destroyBlock(pos)` | 不需要客户端预测与命中判定 |
| `place` | `gameMode.useItemOn(player, level, held, hand, BlockHitResult)` | 与真实右键走同一条服务端路径 |
| `use` | block/entity/item 三分支（`useItemOn` / `interactOn` / `useItem`） | 同上 |
| `attack` | `player.attack(entity)` | 服务端权威伤害计算 |
| `pick` | `BlockState.getCloneItemStack(...)` + 放进选中槽 | 创造「pick block」的服务端等价物 |
| `inventory` | `menu.clicked(...)` / `InventoryMenu` 直改 / `clearContent` | §8.3 早就指出的最高性价比落点 |

`place` 的一个报告 bug 顺手修掉：成功时真正变化的是 `pos.relative(face)` 而不是 `pos`（`pos` 是**被右键的支撑方块**），
原来把 `changed` 读在支撑方块上，创造模式放石头会得到 `changed:false` 的假阴性。

### 13.2 十个 inventory op（服务端侧）

`give` / `clear` / `set-selected` / `click` / `slots`（观察）/ `drop` / `close` / `button` / `trade` / `rename`。

后四个是「玩家打不开 GUI，但服务端能按同一个 handler 语义代跑」的直接产物：`drop` → `player.drop(all)`、
`close` → `doCloseContainer()`、`button` → `menu.clickMenuButton(player, id)`、`trade` → `MerchantMenu.setSelectionHint`、
`rename` → `AnvilMenu.setItemName`。没有菜单/没开铁匠台时**一律结构化报错**，不假装成功。

### 13.3 两个可重跑套件

| 套件 | 文件 | 用例 | 最后一次结果 |
|---|---|---|---|
| 客户端动作 | `tmp/p4a-action-suite.ps1` | 19 | **PASS=19 FAIL=0 total=19**（`tmp/p4a-suite-result.log`） |
| 纯服务端动作 | `tmp/p4a-server-suite.ps1` | 20 | **PASS=20 FAIL=0 total=20**（`tmp/p4a-server-suite-result.log`） |

服务端套件 20 条的分布：3 条元信息（`actionBackend` / `actions` 清单 / 严格解析）、
13 条动作、4 条「响亮失败」（未知 gamemode 400、不存在实体 404、没有商人界面 404、无人在线 404）。

### 13.4 四条新的「验证方法自身错」

1. **`give` 会与同种物品合并** —— 手里已有 32 石再给 32，会变成 64，按 `count==32` 找槽位就落空。
   套件先 `clear` 再 `give`，断言才与历史状态无关。
2. **`set-selected` 有两套编号** —— 菜单槽 36 对应快捷栏 0。客户端侧只认 0..8（快捷栏），
   服务端侧认菜单槽位；套件里两个断言分开写（`menuSlot=36 hotbar=0 mainhand=stone`）。
3. **NoAI 僵尸也算「附近有怪物」** —— 服务端动作套件在测试区留的僵尸会静默阻止入睡，
   让客户端套件的 sleep 偶发 FAIL。修法：以床为中心 `execute positioned ... run kill @e[type=zombie,distance=..16]`。
4. **启动脚本的 stdout 不能接管道** —— `pwsh -File start.ps1 | Select-Object -Last 4` 时，
   被拉起的 java 服继承了 stdout 句柄，管道永远不返回（表现成「后台跑了 9 分钟」）。
   起服一律内联 `Start-Process` + 轮询，不套管道。

### 13.5 三家一致性（同一份共享实现）

| 平台 | loader | actionBackend | actions | 严格解析 | 无人在线 |
|---|---|---|---|---|---|
| Fabric `:39011` | fabric | server | 9 个 | `400 BAD_REQUEST` | `404 PLAYER_NOT_FOUND` |
| Forge `:39012` | forge | server | 9 个 | `400 BAD_REQUEST` | `404 PLAYER_NOT_FOUND` |
| NeoForge `:39013` | neoforge | server | 9 个 | `400 BAD_REQUEST` | `404 PLAYER_NOT_FOUND` |

`/item`、`/command`、`/world/block` 三家同构；15 条路由矩阵未因本批改动回退。

### 13.6 产物

| 平台 | 类数 | jar | 日志 |
|---|---:|---|---|
| Fabric | 19 | **104,532 B** | `tmp/build-fabric-p4op2.log` |
| Forge | 15 | **54,589 B** | `tmp/build-forge-p4op2.log` |
| NeoForge | 17 | **58,144 B** | `tmp/build-neoforge-p4op2.log` |

`--rerun-tasks` 全量构建，三家 `警告/错误` 计数 **0**；jar 内 class 数用 zip 条目复核（不是看 `BUILD SUCCESSFUL`）。
三份 jar 已同步到 `<MC_ROOT>\Server{,1,2}\mods\` 并逐家起服复验。

---

## 14. 客户端入口：Forge / NeoForge（2026-10-20）

P4 第二段最后一块：三家现在都有**客户端探针**，`/status` 的 `actionBackend` 在客户端是 `client`、在专用服务器是 `server`。

### 14.1 构建面改动（两条 build.gradle）

Forge 与 NeoForge 的构建都**只有一个 `main` source set**（与 Fabric 的 `splitEnvironmentSourceSets()` 不同），
所以客户端代码只能编进同一个 jar：

```groovy
// lab/forge/build.gradle / lab/neoforge/build.gradle 末尾
sourceSets.main.java.srcDirs += file('../shared/src/client/java')
```

代价写明在注释里：专用服务器的 jar 里也有 `net.minecraft.client.*` 的引用，
靠 **`dist` 守卫**保证不被加载（见 14.3）。

### 14.2 三家入口机制对照

| 平台 | 触发点 | 为什么是这个 |
|---|---|---|
| Fabric | `ClientModInitializer.onInitializeClient()` + `ClientLifecycleEvents.CLIENT_STARTED` | 平台原生设计（`fabric.mod.json` 的 `client` 入口） |
| NeoForge | `ClientStartedEvent` / `ClientStoppingEvent`（游戏总线） | NeoForge 有与 Fabric 完全对位的客户端生命周期事件 |
| Forge | **第一个 `TickEvent.ClientTickEvent.Post`**（游戏总线，一次触发） | Forge 26.3 **没有** client-started 游戏事件：`FMLClientSetupEvent` 在 mod 总线上、要 `BusGroup`，而 EventBus 7 已删除 `@Mod.EventBusSubscriber` / `@SubscribeEvent`（javap 实测：两者都不在 jar 里）；给 `@Mod` 构造器加 `ModContainer` 参数只为拿 BusGroup 不值 |

Forge 的一次性启动用 `private static boolean started` 守住；`ClientProbeServer.start` 本身幂等。

### 14.3 dist 守卫与「服务端不加载」的证据

两家入口都在探针 `@Mod` 构造器里按 dist 分支：

```java
if (FMLEnvironment.dist == Dist.CLIENT) {            // Forge
    ForgeClientProbe.install();
}
if (FMLEnvironment.getDist() == Dist.CLIENT) {       // NeoForge
    NeoForgeClientProbe.install();
}
```

三台专用服务器加载**含客户端类的新 jar** 后逐家实测：

| 平台 | `客户端入口已加载` 日志 | 客户端探针监听 39002 | 服务端探针 | `/status` |
|---|---|---|---|---|
| Fabric | 0 条 | 无 | `Mcdev Probe listening on 127.0.0.1:39011` | `loader=fabric backend=server` |
| Forge | 0 条 | 无 | `…:39012` | `loader=forge backend=server` |
| NeoForge | 0 条 | 无 | `…:39013` | `loader=neoforge backend=server` |

三家的 `actions` 都是九条：`teleport,gamemode,ability,mine,place,use,attack,pick,inventory`；
`capabilities` 均为 `core,command,world,inventory,craft,player,entity,action`。

### 14.4 编译事故：`Abilities.mayfly` 在 NeoForge 上已弃用

把客户端源集接进 NeoForge 构建后立刻报两条：

```
ClientActionBackend.java:784: 警告: [deprecation] Abilities中的mayfly已过时
ClientActionBackend.java:793: 警告: [deprecation] Abilities中的mayfly已过时
```

`javap -v` 确认 NeoForge 补丁给 `Abilities.mayfly` 加了 `@Deprecated`（字段本身还在）。
修法不是加 `@SuppressWarnings` 掩盖，而是**不写不读**：`creative` / `spectator` 下
服务端本来就会通过 `ClientboundPlayerAbilitiesPacket` 把 `mayfly` 置 true，
客户端自己再写一遍既多余、又会在三家共用源码上引入弃用告警。
响应里去掉 `mayfly` 字段（服务端 `/player/state` 出于同一原因早就去掉了）。

### 14.5 产物与验证

| 平台 | 类数 | 其中客户端类 | jar | 日志 |
|---|---:|---:|---|---|
| Fabric | 19 | 3 | **104,485 B** | `tmp/build-fabric-cliententry.log` |
| Forge | 19 | 3 | **100,733 B** | `tmp/build-forge-cliententry.log` |
| NeoForge | 21 | 3 | **104,289 B** | `tmp/build-neoforge-cliententry.log` |

三家 `--rerun-tasks build`、`警告/错误` 计数 **0**；类数用 zip 条目复核（3 个客户端类 =
`ClientProbeServer` + `ClientActionBackend` + `ScriptedInput`）。三份 jar 已同步到
`<MC_ROOT>\Server{,1,2}\mods\`，起服后 `/status` 逐家核对，已停服（`2556x/3900x` 无监听）。

### 14.6 未做（明确留给下次）

- 两家的客户端入口没有走 `splitEnvironmentSourceSets` 那种编译期端边界（平台不支持），
  端边界只靠 `dist` 守卫 + Iron law（加载器 API 只出现在 platform 包内）。
- ~~Forge / NeoForge 的 `runClient` 实跑~~ —— 2026-10-19 21:20 已完成，见 §14.7。

### 14.7 三加载器 `runClient` 实跑（2026-10-19 20:47–21:20）

三家客户端各自连本家测试服，跑的是同一份 `tmp/p4a-action-suite.ps1`（19 条）：

| 平台 | 客户端 | 服务端（游戏/探针） | 套件结果 |
|---|---|---|---|
| Fabric | `fabric runClient`（探针 39002） | 25566 / 39011 | `SUMMARY PASS=19 FAIL=0 total=19` |
| Forge | `forge runClient`（探针 39002） | 25567 / 39012 | `SUMMARY PASS=19 FAIL=0 total=19` |
| NeoForge | `neoforge runClient`（探针 39002） | 25568 / 39013 | `SUMMARY PASS=19 FAIL=0 total=19` |

关键逐条证据（Forge 一轮，其余两家同构）：

```
mine        PASS [200] broken=True ticks=0 serverBlock=minecraft:air
place       PASS [200] serverBlock=minecraft:stone
pick        PASS [200] mainhand=minecraft:diamond_block
attack      PASS [200] 20 -> 19.807775 damaged=True
sleep       PASS [200] sleeping=True serverSleeping=True
respawn     PASS notDead=[400 BAD_REQUEST] afterKill=[200] alive=True health=20
server/gamemode(strict) PASS [400] unknown gamemode 'nonsense' (...)
```

- Forge 首次连接曾一直 `You are not white-listed on this server!`（见 §14.9 第 2 条）。
- NeoForge 客户端在 26.3 上不需要额外的 `--add-opens`（runClient 由 moddev 生成参数）。
- **三家共用同一个客户端探针端口 39002**：同一时刻只能跑一个客户端，`p4a-start-any-client.ps1`
  会先按端口杀掉上一个客户端再起新的。

### 14.8 测试姿态：默认创造（用户 2026-10-19 提出的规则）

现象：客户端首次连服默认生存，长套件跑到一半测试角色被怪物打死，后续位置/状态断言全歪
（用户当时也看到了「测试用角色被怪物杀掉」）。

规则：**测试默认创造**；只有用例本身要求其它模式时才临时切，用完立刻切回。
落地三处：

1. `tmp/p4a-action-suite.ps1` 第 1 步读 `/status.gamemode`，非 `creative` 就
   `gamemode creative <player>` 并复核；顺带删掉一行拼错的 `Cmd 'gamemode creative ' + $name`。
2. `sleep`（要求生存）与 `respawn`（要求先死）两个用例结束后立刻 `gamemode creative` 切回。
3. 三台测试服 `server.properties` 的 `gamemode=survival` → `creative`（备份
   `.bak-20261019-2059`，enctool 自身另留 `.encbak`），运行期同时跑 `defaultgamemode creative`
   （服务端聊天行确认 `The default game mode is now Creative Mode`）。

为什么不用 `peaceful`：`attack` 用例需要敌对生物，`peaceful` 下僵尸会被清除。
创造模式的免伤已足够；只有 `sleep` / `respawn` 短暂切生存，且这两处都先清怪。

### 14.9 本轮修掉的三个工具/协议坑

1. **`/connect` 只认 query，不认 JSON body**：套件按 body 发 `{"host","port"}` 被静默忽略，
   于是连到默认 25566 —— 而目标服是 25567/25568，表现为「客户端永远进不了世界」。
   修：`handleConnect` 同时接受 query 与 body（**body 优先**），body 不是对象时回 400
   `invalid json body (expected {"host":..,"port":..})`。
2. **白名单 UUID 不匹配**：`whitelist add Dev` 写入的是 `5084e6f3-…`，而 `online-mode=false`
   的登录用的是离线 UUID `<OFFLINE_UUID>`，一直
   `You are not white-listed on this server!`。修：把 `whitelist.json` 改写成离线 UUID +
   `whitelist reload`（`Server1` 备份 `.bak-20261019-2050`、`Server2` 备份 `-2058`）。
3. **客户端日志句柄**：`p4a-start-any-client.ps1` 在 java 未完全退出时就 `Remove-Item` 日志 →
   `being used by another process`。修：先按端口杀、轮询端口释放，再最多重试 20 次删日志。

另记一条运维坑（本轮踩了两次）：**起服脚本不要接管道**（`| Select-Object`）也不要
`*>` 重定向外层调用 —— java 子进程继承句柄，管道/重定向永不收口，命令看起来「卡死」
（一次 9 分钟、一次 7 分钟），而服务器其实早已 READY。启动一律用
`Start-Process ... -WindowStyle Hidden` 脱离，再在别处轮询 `/status`。

### 14.10 最终产物（2026-10-19 21:11）

| 平台 | jar | 类数 | 构建 |
|---|---:|---:|---|
| Fabric | **104,955 B** | 19 | `BUILD SUCCESSFUL in 9s` |
| Forge | **101,203 B** | 19 | `BUILD SUCCESSFUL in 10s` |
| NeoForge | **104,759 B** | 21 | `BUILD SUCCESSFUL in 6s` |

三家 `警告/错误` 计数 0；三份 jar 已同步到 `<MC_ROOT>\Server{,1,2}\mods\`。
Fabric 服用新 jar 起服（READY 11.5s）后套件 19/19，是本节数字的最终来源。

---

## 15. 第二批：聊天 / 告示牌 / 命令方块（2026-10-20）

### 15.1 范围与取舍

用户要求「把聊天(6 包) / 特殊方块UI(7 包) 那批全补上」。落地为三个面、六条路由：

| 面 | 客户端端点 | 服务端端点 |
|---|---|---|
| 聊天 | `POST /chat/send {text}` | —（本就有 `/command` 等价通道） |
| 告示牌 | `POST /block/sign/edit`、`GET /block/sign` | `GET /block/sign`、`POST /block/sign {op:read\|open\|set}` |
| 命令方块 | `GET /block/command-block` | `GET /block/command-block`、`POST /block/command-block {op:read\|set\|run}` |

`/chat/suggest` 故意不开：`ServerboundCommandSuggestionPacket` 的响应是异步的 `ClientboundCommandSuggestionsPacket`，
而这个项目的所有端点都是「同步返回结果」，没有「等推送」的基础设施；开了只能返回「已发出，结果不知道」，
正是第 2 节要消灭的那类假成功。要开就先给探针加一条事件订阅通道，那是独立的一块活。

### 15.2 告示牌为什么需要两条路

`ServerboundSignUpdatePacket` 不是「想发就发」：服务端 `ServerGamePacketListenerImpl.handleSignUpdate` 会先
`filterTextPacket`、再进 `SignBlockEntity.updateSignText`，而后者的守卫是

```java
if (!isWaxed() && player.getUUID().equals(getPlayerWhoMayEdit()) && level != null) { ...写... }
else { LOGGER.warn("Player {} just tried to change non-editable sign", ...); return; }
```

`getPlayerWhoMayEdit()` 只有 `SignBlock.useWithoutItem`（右键开编辑界面）才会设。所以实测第一版
「直接发文本包」的结果是 `sent:true` 而服务端回读四行全空——**静默拒绝**。

修法：`/block/sign/edit` 在发文本包**之前**先 `gameMode.useItemOn(player, MAIN_HAND, hit)` 模拟右键开界面
（`packets` 里如实列出 `ServerboundUseItemOnPacket(open editor), ServerboundSignUpdatePacket`）。
服务端侧另给 `op=open`（代劳授权，之后要真客户端发包）与 `op=set`（**不需要任何在线玩家**，直接写方块实体 +
`setChanged` + `sendBlockUpdated`，响应里 `note` 写明「不走文本包路径」）。

### 15.3 实测证据

| 用例 | 平台 | 结果 |
|---|---|---|
| `POST :39002/chat/send {text}` | Fabric 客户端 | `sent:true, via:chat`；服务端日志 `[Not Secure] <Player584> mcdev chat ok` |
| `POST :39002/chat/send {text:"/list"}` | Fabric 客户端 | `via:command`（`ServerboundChatCommandPacket`），服务端回 `There are 1 of a max of 20 players online` |
| `POST :39002/block/sign/edit` | Fabric 客户端 | 服务端回读 `lines:["MCDev line1","line2","line3","line4"]`、`hasMessage:true` |
| `POST :39012/block/command-block {op:set,command:"say 39012-cb-ok"}` → `{op:run}` | Forge，**零玩家** | `successCount:1`；日志 `[Not Secure] [@] 39012-cb-ok` |
| 同上 | NeoForge，**零玩家** | `successCount:1`；日志 `[Not Secure] [@] 39013-cb-ok` |
| `POST :39012/block/sign {op:set,lines:[...]}` → `GET` | Forge，**零玩家** | 写进即读回一致、`hasMessage:true` |

顺带证实了「服务端探针才是权威」：客户端 `GET /block/command-block` 读到的 `command` 是空串（方块实体数据没同步给客户端），
同一条命令服务端读回 `say …` 并真的跑出了效果。

### 15.4 本段两个坑

1. **同一个错误两端状态码不一致**：连服前调 `GET /block/sign` 客户端回 **500**、服务端回 **404**。
   根因是客户端 `onClient()` 把游戏线程的异常包成 `ExecutionException` 抛出，handler 里
   `catch (IllegalStateException)` 永远接不到，落进通用 catch → 500。修法：`onClient` 里拆包后原样抛回
   `RuntimeException`/`Error`（注释写明理由），改后同一请求回 `404 {"message":"client is not in a world"}`。
2. **套件里 `setblock` 看似成功其实没生效**：`result:0` 不是成功布尔，方块远在未加载区块时命令静默失败；
   告示牌用例因此拿到 `sign is not a sign`。修法：用例 4 之前先 `tp` 到牌旁边并等落地。
3. **`/block/sign/edit` 会把客户端留在 `SignEditScreen` 里**：`useItemOn` 开的编辑界面不会因为发完文本包就关。
   第一次在 Forge 上复跑套件时 25 条里 **9 条 FAIL**（`move(walk) distance=0.000`、`attack/use(entity)` 500、
   `mine/pick` 404、`sleep landed=False`），而 `GET /ui/snapshot` 一眼就是 `screen:"SignEditScreen"`。
   修法：发包后在游戏线程里 `mc.setScreenAndShow(null)` 并回 `screenClosed`；修后三家复跑都是 25/25。
   ——**这条修改的价值不在「多一个字段」，而在「动作做完不留残余状态」**，否则套件之间会互相污染。

### 15.5 产物（2026-10-20 21:32）

| 平台 | jar | 类数 | 构建 |
|---|---:|---:|---|
| Fabric | **115,621 B** | 19 | `BUILD SUCCESSFUL`（零警告） |
| Forge | **111,870 B** | 19 | `BUILD SUCCESSFUL`（零警告） |
| NeoForge | **115,425 B** | 21 | `BUILD SUCCESSFUL`（零警告） |

三份 jar 已同步到 `<MC_ROOT>\Server{,1,2}\mods\`（最终 115,621 / 111,870 / 115,425 B）；起服后 `/status.capabilities` 在两家都多出 `block_extra`
（`core,command,world,inventory,craft,player,entity,block_extra,action`）。客户端套件扩到 **25 例**：
`SUMMARY PASS=25 FAIL=0 total=25`（前 19 条动作 + 第 4 节 6 条：聊天、聊天命令、写牌回读、命令方块写+读、跑、三条响亮失败）。
**三家客户端各跑一遍都是 25/25**（Fabric `Player380` / Forge `Dev` / NeoForge `Dev`），服务端套件 20/20，15 路由矩阵 `200=12 501=3`。

### 15.6 58 包覆盖更新

新增实际驱动/触及的包：`ServerboundSignUpdatePacket`、`ServerboundChatPacket`、`ServerboundChatCommandPacket`、
`ServerboundUseItemOnPacket`（已计）。累计 **24/58**（原 21 + 招牌 1 + 聊天 2）。
仍未覆盖的 34 个是：容器菜单里没有端点的那些（`ContainerButtonClick` 等价已由 `/friend/…` 之外的 op 覆盖、
`PlaceRecipe`、`RenameItem`、`SelectTrade` 之外的菜单交互）、`ClientCommand` 的 stats/gamerule 查询、
`TestInstanceBlockAction`、`PaddleBoat`、`SeenAdvancements`、`ChatAck`/`ChatSessionUpdate` 等协议内部包。
结论与上一版一致：**不追求按包覆盖，按「测试需要什么动作」覆盖**。

---

## §16 端到端编排脚本 `devrun.ps1`（2026-10-20 22:00）

### 16.1 为什么现在做

到 §15 为止，探针能力已经够了（两端 9 + 12 个动作、聊天/告示牌/命令方块、两个回归套件），
但**每次验证都要人工按顺序敲**：起服 → 起客户端 → 连服 → op → 摆测试区 → 逐条 curl → 停。
这些步骤的次序、等待条件、清理动作全是隐性的，写进文档也只能靠人复现。

按用户「开发套件这边先往下做」的指示，本段把这条链固化成脚本：

- `scripts/devrun.ps1`（385 行）：一条命令跑完六个阶段，产出一份可归档的取证目录。
- `scenarios/smoke.json`：场景文件（当前 5 条用例）。

### 16.2 六个阶段

| 阶段 | 做什么 | 失败处理 |
|---|---|---|
| 1/6 准备 | 按 `-Family` 选定服务端目录/端口/启动参数；核对 `mods/` 里的探针 jar | jar 缺失直接抛错（不猜） |
| 2/6 起服务端 | `Start-Process java` + 轮询 `GET /status` 到 200（默认 240s） | 超时抛错，日志留在取证目录 |
| 3/6 起客户端 | `Start-Process pwsh` 跑 `gradlew runClient`，轮询客户端探针 `/status` | 同上 |
| 4/6 连接 | `POST /connect?host&port` → 轮询 `/status.player` 非空 → 读玩家名 → `op` | 30 次轮询后仍无玩家则抛错 |
| 5/6 执行场景 | 逐条 `steps` 发请求，按 `expect` 判定 PASS/FAIL | 单条失败不中断，最后汇总 |
| 6/6 取证 | 写 `summary.md`：结论、阶段耗时、用例表、两侧 `/status` 原文、两边日志尾巴 | — |

收尾（`finally`）无条件执行：`POST /quit` 客户端、`POST /command {"cmd":"stop"}` 服务端、
按端口等监听释放（最多 30s），超时只告警不抛错（不能因为「端口还没放」把一个成功场景变成失败）。

### 16.3 实测（Fabric / smoke）

```
[21:55:00] === 1/6 准备（fabric → <MC_ROOT>\Server，游戏 25566，探针 39011） ===
  探针 jar: mcdev_probe-1.0.0.jar 115621 B
[21:55:12] 就绪：loader=fabric probe=39011
[21:55:37] 客户端探针已监听
[21:55:54] 进入世界：player=Player464 / op Player464 → 200
[PASS] server-status 200 / client-status 200 / server-gamemode-creative 200
[PASS] client-walk 200 / loud-failure-unknown-gamemode 400
  结论：PASS=5 FAIL=0 total=5
  阶段耗时（秒）：prepare=3 / server=12.5 / client=22 / connect=17 / scenario=1.7
```

整条链路 **≈2 分钟**，退出码 0。取证目录 `tmp/devrun-fabric-20261019-215500/`。

### 16.4 场景格式

```json
{ "id": "client-walk", "target": "client", "method": "POST", "path": "/action/move",
  "body": { "mode": "walk", "durationMs": 400 },
  "expect": { "status": 200, "json": { "data.moved": true } } }
```

- `target` 只有 `server` / `client` 两种（分别打 3901x / 39002），端口由脚本按 `-Family` 决定。
- `expect.status` 精确比对 HTTP 状态码；`expect.json` 用**点路径**取值（`data.side`、`error.code`），逐条比对字符串/布尔/数字。
- **错误码也是契约**：`loud-failure-unknown-gamemode` 就是故意断言 `400` + `error.code=BAD_REQUEST`。
  一个只测成功路径的套件，会把「静默 200」当成通过。
- `path` / `body` 里可写 **`{{player}}`** 占位符，脚本在发请求前替换成刚进世界的玩家名
  （Fabric 每次启动都是 `PlayerNNN`，Forge/NeoForge 是 `Dev`；写死名字会直接失败）。
- `waitMs`（可选）在**发请求之前** sleep 指定毫秒，用于「tp 之后等落地」这类时序。

### 16.5 第一次跑就抓到的一个真 bug：端口没传

第一轮：服 8s 起来了、探针监听 **`127.0.0.1:39001`**（默认值），而脚本按 `39011` 轮询 → 等 240s 超时。
根因：脚本把 `-Dmcdev.probe.port` 当成了「服务端启动参数」，实际只拼了 `-Xmx2G -jar fabric-26.3.jar nogui`。

修法（`scripts/devrun.ps1:225`）：

```powershell
$javaArgs = @("-Dmcdev.probe.port=$ServerProbe", "-Dmcdev.probe.token=$Token") + $FamilyCfg.Args
```

两条教训：

1. **`-D` 必须在 `-jar` / `@args` 之前**，否则 JVM 不认（Forge/NeoForge 的 `@user_jvm_args.txt` 同理，要排在它前面）。
2. 「脚本等不到就超时」比「探针落在别的端口上还在跑」更好 —— 前者一眼能查，后者会让人以为环境正常、
   然后所有请求都打到空端口上。所以这里**不**加「自动换端口探测」的兜底。

### 16.6 明确不做的事

- **不构建**：假设 `lab/<family>/build/libs/mcdev_probe-1.0.0.jar` 已是最新（构建另跑，见 §13 的核对类数纪律）。
- **不装 mod**：只把探针自己放进 `mods/`；被测 mod 的部署是 P5 的事。
- **不测 UI**：`/ui/*` 有自己的语义（控件树、点击），塞进动作场景会混淆。
- **不做多加载器矩阵**：`-Family` 是参数，要三家就跑三次（三家共用客户端探针端口 39002，不能并行）。
- **不自动重试**：失败就失败，重试会掩盖「时序不稳」，而时序不稳本身就是待查的缺陷。
### 16.7 场景不能依赖世界残留（Forge 那台服踩到的）

`scenarios/actions.json` 的 v1 在 Fabric 上 13/13，到 Forge 上直接掉两条：

| 用例 | 现象 | 根因 |
|---|---|---|
| `inventory-set-selected` | 主手是 `minecraft:rotten_flesh` | `Server1` 的 `testworld` 是早先手工测试留下的世界，玩家快捷栏 0 里还有腐肉；`give` 把石头塞进**另一个空槽**（`Inventory.add` 的语义就是「任意空位」） |
| `server-place` | `changed:false`，目标格仍是 air | 主手是腐肉 —— 腐肉**不能放置**，`useItemOn` 返回 `Pass`，方块没变 |

这不是探针的 bug，是**场景设计的错**：它假定「清空背包 + 给物品 + 选中」就能拿到想要的主手，但这个假定依赖世界状态。

修法两条：

1. **服务端 `/action/inventory` 的 `give` 增加可选 `slot` 与 `select`**（`ServerActionBackend.give`）：
   `slot` 接受 0..8 或 36..44（与 `set-selected` 同一套归一化），直接 `getInventory().setItem(hotbar, stack)`
   + `inventoryMenu.broadcastChanges()`；`select:true` 再置 `setSelectedSlot` 并下发 `ClientboundSetHeldSlotPacket`。
   响应统一带 `selectedSlot` / `item` / `count`，调用方不用二次查询。
2. **场景自己铺路**：`forceload add` + `setblock` 造方块、`fill` 造 5×5 石台、`tp {{player}}` 站上去，
   每条断言后面跟一条 `/world/block` 回读 —— 场景在**任何**世界状态下都成立。

### 16.8 三家实测（2026-10-19 22:13–22:18，同一份 `scenarios/actions.json`）

```
fabric    [22:13:30 → 22:14:21]  PASS=17 FAIL=0 total=17   player=Player963  jar 115,935 B
forge     [22:14:29 → 22:15:51]  PASS=17 FAIL=0 total=17   player=Dev        jar 112,184 B
neoforge  [22:16:53 → 22:18:12]  PASS=17 FAIL=0 total=17   player=Dev        jar 115,739 B
```

十七条里有十一条是「改世界 + 服务端回读」的成对断言（mine→air、place→stone、give→主手、walk→moved），
另外四条是编排面（服务端起停、客户端进世界、op、响亮失败）。取证目录
`tmp/devrun-<family>-20261019-22*/summary.md`，每份都含 17 行表 + 阶段耗时 + 两侧 `/status` + 日志尾巴。

### 16.9 起服脚本的两个「看起来卡死」（都是句柄继承）

| 写法 | 结果 | 原因 |
|---|---|---|
| `pwsh -File start.ps1 ... \| Select-Object -Last 4` | 9 分钟不返回 | java 服继承了父进程的 stdout 句柄，管道要等**所有**写者关闭才收口 |
| `pwsh -File start.ps1 ... > out.txt` | 同样不返回 | 重定向句柄同样被继承；只要 java 还活着，外层命令就收不了尾 |

`devrun.ps1` 因此统一用 `Start-Process -RedirectStandardOutput <file> -PassThru` 起服与起端（句柄给文件、不给调用方），
然后在**前台**轮询 `/status`。这也是为什么 `devrun` 自己可以安全地跑在后台作业里，而「起服脚本」不能接管道。

## 17. 复用与发布：矩阵模式 + `package.ps1`（2026-10-20 22:19–22:25）

### 17.1 `devrun.ps1 -Family all`：三家串行矩阵

```powershell
pwsh -NoProfile -File scripts\devrun.ps1 -Family all -Scenario scenarios\actions.json
```

- 三家**串行**（客户端探针端口 39002 是共用的，不能并行）；每家仍走完整的六阶段 + `finally` 收尾。
- 实现方式是「重新起一个本脚本进程」，不是函数递归：每家独占端口、各自收尾，一家跑挂不会污染下一家。
- 聚合只认每家 `summary.md` 里的 `PASS=/FAIL=total=` 行 + 子进程退出码；任一失败 → 矩阵 exit 1。
- `-Family all` 拒绝 `-EvidenceDir` / `-KeepRunning`（前者会让三家覆盖同一目录，后者会让下一家端口被占）—— 明确报错而不是悄悄忽略。

实测（22:19:51–22:23:53）：

```
family   exit summary                 evidence
fabric      0 PASS=17 FAIL=0 total=17 tmp/devrun-fabric-20261019-221952
forge       0 PASS=17 FAIL=0 total=17 tmp/devrun-forge-20261019-222114
neoforge    0 PASS=17 FAIL=0 total=17 tmp/devrun-neoforge-20261019-222255
矩阵结论：三家全绿
```

四分钟跑完三家 = 原来手工三家各一轮的时间的 1/3。

### 17.2 `scripts/package.ps1`：P5 打包第一版

一条命令产出可发布的 `dist/mcdev-probe-<version>/`：

```
manifest.json                    889 B
mcdev-probe-1.0.0-fabric.jar     115,935 B   sha256 aeebe3f6…3dbc33
mcdev-probe-1.0.0-forge.jar      112,184 B   sha256 10fe404e…d34cf8
mcdev-probe-1.0.0-neoforge.jar   115,739 B   sha256 4b0add3b…67a39
README.md                      1,768 B
SHA256SUMS.txt                   289 B
```

核对链（任一不过就 exit 1，**不产出「看起来成功」的包**）：

| 关卡 | 判据 |
|---|---|
| 构建 | 三家 `--rerun-tasks build` 必须成功（Forge 增量会漏类，见 §12.5） |
| 零警告 | javac `警告:/warning:` 计数必须为 0 |
| 类数 | 必须等于基线 fabric 19 / forge 19 / neoforge 21 |
| 元数据 | jar 内必须有 `fabric.mod.json` / `META-INF/mods.toml` / `META-INF/neoforge.mods.toml` |
| 校验和 | 产物落盘后实测 `SHA256SUMS.txt` 三条全部 OK（本次实测 `verify=True`） |

`-SkipBuild` 只重打包（核对照做），`-Force` 才允许覆盖已存在的版本目录 —— 默认报错，避免把验证过的产物
悄悄换掉。

## 18. 发布形态改为「一个套件」（2026-10-20 22:40，用户定）

用户原话：*「另外 github 和之后发布的话，作为整体一个套件进行发布，不需要多个 jar 文件之类的」*。

**决策**：对外只有**一个**发布物 —— `dist/mcdev-probe-suite-<version>.zip`。版本号、清单、README、校验和都只有一份；
用户解开后跑一个入口脚本，选加载器。三个加载器 jar 仍在套件里（`mods/`），因为入口/元数据不同、**物理上无法合并成一个 jar**，
但它们不再是「三个各自发布的东西」。

### 18.1 套件内容

| 路径 | 内容 |
|---|---|
| `mods/` | `mcdev-probe-<v>-fabric.jar` / `-forge.jar` / `-neoforge.jar`（字节与已验证部署件一致） |
| `scripts/install.ps1` | **唯一入口**：`-Loader` + `-Target <实例目录>`（放进 `<Target>\mods\`，可选 `-DshPlugin`），带 sha256 校验、`-DryRun`、装了多份探针时告警 |
| `scripts/install-dsh-plugin.ps1` | DSH 插件安装（幂等、改前备份、可卸载） |
| `scripts/devrun.ps1` + `scenarios/` | 端到端编排与场景（`smoke.json` 5 步 / `actions.json` 17 步） |
| `dsh-plugin/mcdev-devrun/` | DSH 插件源码（`mcdev_devrun` / `mcdev_scenarios` 两个工具） |
| `README.md` / `LICENSE` / `SHA256SUMS.txt` / `manifest.json` | 套件级说明、许可、逐文件校验和、清单（`kind:"suite"`、`entrypoint:"scripts/install.ps1"`） |

### 18.2 `package.ps1` v2 的核对链（任一不过 exit 1）

构建成功 → javac 零警告 → 类数等于基线（19/19/21）→ jar 内有 mod 元数据 → 组装 → **zip 复验**（三份 jar + `install.ps1` + 插件 `dsh.mjs` + 两个场景 + README/清单必须在 zip 里）→ **逐文件校验和全对**。

### 18.3 实测证据（2026-10-20 22:41，`-SkipBuild -Force`）

| 项 | 值 |
|---|---|
| 单文件套件 | `dist/mcdev-probe-suite-1.0.0.zip`，**371,888 B**，15 个条目 |
| 套件内 jar | 115,935 / 112,184 / 115,739 B，类数 19 / 19 / 21，action 类 9 / 9 / 9 |
| 与部署件一致性 | 三家 `sha256` **完全相同**（`aeebe3f6…` / `10fe404e…` / `4b0add3b…`）⇒ 套件里的就是三家真机验证过的那三份 |
| 校验和 | 13 个文件逐个复验通过 |

### 18.4 解压后的三个实测（全部通过）

| 用例 | 结果 |
|---|---|
| 正常安装 | `install.ps1 -Loader forge -Target <实例>` → `清单校验: ok` + `✅ 已放入 …\mods\…-forge.jar` |
| 目标目录不存在 | `❌ 目标目录不存在：…（拼错了？本脚本不会凭空建实例目录）`，exit 1 |
| jar 被篡改 | `❌ … 的 SHA256 与 SHA256SUMS.txt 不一致（期望 aeebe3f6…，实际 e314167a…）`，exit 1 |
| 装了第二份探针 | `⚠ mods/ 里还有别的探针 jar，同时加载会冲突： mcdev-probe-1.0.0-forge.jar` |

### 18.5 保留 / 不做

- 旧的 `dist/mcdev-probe-1.0.0/`（v1 扁平三 jar 布局）**不再作为发布物**，留着只当对比证据，可随时删。
- 不做「一个 jar 通吃三家」：三家入口类与元数据文件（`fabric.mod.json` / `mods.toml` / `neoforge.mods.toml`）由加载器各自解析，合并只能靠 jar-in-jar 或另写 loader 探测，收益为零、风险不小。
- GitHub Release 形态：一个 tag、一个 zip 附件、正文贴 `manifest.json` 摘要即可（发布动作本身仍待用户决定渠道细节）。