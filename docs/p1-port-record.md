# P1 共享库移植 —— 实测记录

> 实测日期：2026-10-19。目标平台：Fabric 26.3（`lab/fabric`），服务端 `<MC_ROOT>\Server`。
> 状态：**✅ 全部验收项通过**。本文所有数字都来自真实执行，命令与输出片段随附。

---

## 1. 计划 vs 实际

计划（`mc26.3-multi-loader-probe-feasibility.md` §13 P1）写的是「8 个文件 1,114 行 + mixin 改造 + 15 行 `RegistryBridge`」。实际落地比计划简单，也更小：

| 计划项 | 实际 | 差异原因 |
|---|---|---|
| 8 文件 / 1,114 行 | **6 文件 / 934 行** + 新增 `ProbeLog.java`(16) | `ClientProbeServer`(788) 归 P3；`FakePlayers`(66) 归 P2/P4；`McdevProbe`(65) 由平台入口替代 |
| `ServerLevelMixin` 改造 | **整份删除，P1 零 mixin** | 26.3 有公开的 tick rate 管理 API |
| 21 行 `ForgeRegistries` → `BuiltInRegistries` | 13 处 | `ClientProbeServer` 那 6 处随 P3 走 |
| 15 行 `RegistryBridge` 接口 | **不需要** | `BuiltInRegistries.BLOCK/.ITEM` 本身就是 `net.minecraft.*`，不违反共享库铁律 |

### 1.1 为什么 mixin 可以整份删掉

原探针唯一的 mixin 是 `ServerLevelMixin`（26 行），用 `@Inject(method="tick(...)", at=HEAD, cancellable=true, remap=false)` 冻结世界。

26.3 的原生路径：

```
MinecraftServer.tickRateManager() → net.minecraft.server.ServerTickRateManager
  setFrozen(boolean) / isFrozen()
  frozenTicksToRun() / setFrozenTicksToRun(int)
  stepGameIfPaused(int) / stopStepping()
  runsNormally() / isSteppingForward()
  tickRate() / millisecondsPerTick() / nanosecondsPerTick()
```

`net.minecraft.server.commands.TickCommand` 就是用它实现 `/tick freeze` / `/tick step N` 的。⇒ 冻结/步进不再需要 mixin。

**连带影响**：`refmap`、`compatibilityLevel`、Forge 的 `MixinConfigs` MANIFEST 条目、三家的 mixin 注册通道差异——**这些成本全部消失**。P0 的 V7 变成了「为将来可能需要 mixin 时预留的知识」，而不是 P1 的前置。

---

## 2. 落盘文件

`lab/shared/src/main/java/com/mcdev/probe/`（实测行数）：

| 文件 | 行数 | 说明 |
|---|---|---|
| `ProbeServer.java` | 650 | 15 条服务端路由 + 路由表 |
| `RunRecorder.java` | 77 | 快照写盘 |
| `ProbeLogs.java` | 71 | 环形日志缓冲 |
| `ProbeCommands.java` | 60 | `/mcdev status\|freeze\|step`（`fakeplayer` 子命令已按计划移除） |
| `TickControl.java` | 60 | 包 `ServerTickRateManager`，`attach`/`detach` |
| `ProbeLog.java` | 16 | 新增，slf4j 通道名 `mcdev-probe` |
| **合计** | **934** | |

平台侧：`lab/fabric/src/main/java/com/mcdev/probe/fabric/McdevProbeFabric.java`（52 行，`DedicatedServerModInitializer`）。

---

## 3. 17 个 API 演进错误（1.20.1 → 26.3）

第一次 `compileJava` 报 17 个错误，**全部是 API 演进，没有一个是加载器耦合**：

| # | 1.20.1 | 26.3 | 备注 |
|---|---|---|---|
| 1 | `level.getDayTime()` | `level.getOverworldClockTime()` | `getDayTime` 已从 `Level` 移除 |
| 2 | `version.getName()` | `version.name()` | `WorldVersion` record 化 |
| 3 | `performPrefixedCommand(...)` → int | → `void` | 返回值消失，`/command` 的 `result` 恒 0 |
| 4 | `new Identifier(String)` | `Identifier.parse(String)` | 构造器转私有 |
| 5 | `ResourceKey.location()` | `ResourceKey.identifier()` | 全局改名 |
| 6 | `getAnalogOutputSignal(Level, BlockPos)` | `+ Direction` | 多一个参数 |
| 7 | `chunkMap.saveWithoutMetadata()` | `saveWithoutMetadata(HolderLookup.Provider)` | 传 `mc.registryAccess()` |
| 8 | `RecipeManager.byKey(Identifier)` | `byKey(ResourceKey<Recipe<?>>)` → `Optional<RecipeHolder<?>>` | 需 `ResourceKey.create(Registries.RECIPE, id)` |
| 9 | `Recipe#getIngredients()` | `placementInfo().ingredients()` | |
| 10 | `Recipe#getResultItem(RegistryAccess)` | `display()` → `SlotDisplay.resolveForFirstStack(ContextMap.EMPTY)` | 结果展示彻底组件化 |
| 11 | `Ingredient#getItems()` | 已 deprecated → `items()` | 本项目只用 `isEmpty()`，无需替换 |
| 12 | `Inventory.items` 字段 | `getNonEquipmentItems()` | 装备槽从背包里拆出去了 |
| 13 | `Player.drop(ItemStack, boolean)` | `ServerPlayer.drop(ItemStack, boolean, Prediction.SERVER_ONLY)` | 多一个 `Prediction` |
| 14 | `TagParser.parseTag(String)` | `parseCompoundFully(String)` | |
| 15 | `ItemStack#setTag(CompoundTag)` | `ItemStack.CODEC.parse(NbtOps.INSTANCE, tag)` | NBT 直写被关掉，只能走 codec |
| 16 | `ResourceLocation.tryParse` | `Identifier.tryParse` | |
| 17 | `ForgeRegistries.BLOCKS.getKey(x)` / `ITEMS.getValue(id)` | `BuiltInRegistries.BLOCK.getKey(x)` / `.ITEM.getValue(id)` | 13 处，`getKey` 返回 `Identifier` |

**结论**：`shared/` 层零加载器耦合这条铁律成立——17 个错误里没有一个需要 `net.fabricmc` / `net.minecraftforge` / `net.neoforged`。

---

## 4. 验收证据

### 4.1 编译零警告

```
> gradlew clean build --console=plain
> Task :compileJava
> Task :jar
BUILD SUCCESSFUL in 4s
8 actionable tasks: 8 executed
EXITCODE=0

警告扫描: 0 条警告 / 0 条 NOTE
```

构建脚本里开了 `-Xlint:deprecation` 且 `deprecation = true`（`lab/fabric/build.gradle`），所以 deprecated 用法会被报出来。

### 4.2 服务端 15 条路由（手动 curl 全量）

启动：`<JAVA_EXE> -Xms1G -Xmx2G -Dmcdev.probe.port=39011 -jar fabric-26.3.jar nogui`，cwd=`<MC_ROOT>\Server`。

| 路由 | 方法 | code | 响应要点 |
|---|---|---|---|
| `/status` | GET | **200** | `side=server` `tick` `gameTime` `dayTime` `players=0` `maxPlayers=8` `level=testworld` `version=26.3` `frozen` `stepBudget` |
| `/log/tail?n=3` | GET | **200** | `lines[]` 带 `time/level/logger/thread/message` |
| `/command` | POST | **200** | `{"cmd":"list","result":0}` |
| `/world/block?x=0&y=64&z=0` | GET | **200** | `block=minecraft:air` `state` `analog=0` |
| `/world/break?x=1&y=64&z=0` | POST | **200** | `blockBefore` `removed=false` `drops[]`（空中的方块，正确返回 false） |
| `/world/mine?...&player=Dev` | POST | **200** | `found=false` `error=player not online`（无假玩家，见 §5） |
| `/items?x=0&y=64&z=0&r=2` | GET | **200** | `drops[]` `total=0` |
| `/craft?...&player=Dev` | POST | **200** | `crafted=false` `error=player not online`（同上） |
| `/recipe?id=minecraft:stick` | GET | **200** | `found=true` `item=minecraft:stick` `count=4` |
| `/loot?block=minecraft:stone&...` | GET | **200** | `found=true` `drops=[{item:minecraft:cobblestone,count:1}]` |
| `/item?item=minecraft:stone` | GET | **200** | `found=true` `visible=false` `width=0` `color=16711680` |
| `/run/dump?n=5` | GET | **200** | `runId` `startedAt` `tick` `gameTime` `frozen` `logs[]` `path` |
| `/screenshot` | GET | **501** | `client-only endpoint; this probe is running on the server` |
| `/ui/snapshot` | GET | **501** | 同上 |
| `/ui/click` | GET | **501** | 同上 |

**汇总：12× 200 + 3× 501 = 15/15 全部按设计响应，0 失败。**

### 4.3 `/mcdev` 命令

```
[/command {"cmd":"mcdev status"}]      → {"ok":true,"data":{"cmd":"mcdev status","result":0}}
[/command {"cmd":"mcdev freeze true"}] → {"ok":true,"data":{"cmd":"mcdev freeze true","result":0}}
[/command {"cmd":"mcdev freeze false"} → {"ok":true,"data":{"cmd":"mcdev freeze false","result":0}}
```

### 4.4 冻结语义（重要纠正）

```
        t0     t1(+3s)  t2(+6s)      ← frozen=true 期间
tick    854     916      977   （照涨）
gameTime 811     812      812   （冻住）
dayTime  811     812      812   （冻住）
frozen 字段: true

解冻后 3s: tick=1041  gameTime=873
```

`MinecraftServer.getTickCount()` 数的是**服务端主循环次数**，`/tick freeze` 同样不停它——所以 `tick` 不能当冻结判据。要看 `gameTime` / `dayTime`。这个坑写进了 `/status` 字段说明。

---

## 5. 两条留到后续的问题

1. **`/craft` 与 `/world/mine` 需要在线玩家**：当前返回 `{"found":false,"error":"player not online"}`。这是**正确行为**（本来就要玩家上下文），不是缺陷。假玩家在 P2/P4。
2. **探针对 fabric-api 是硬依赖**：`CommandRegistrationCallback` 来自 fabric-api，缺它直接 `HARD_DEP_NO_CANDIDATE modid 1.0.0 {depends fabric-api @ [*]}` 拒绝加载。P1 期间用 `lab/cache/fabric-api-0.163.0+26.3.jar`（1,774,707 B）垫上。
   **P2 要决定**：继续依赖 fabric-api，还是改用 loader 原生命令注册去掉这条依赖（少一个分发物料）。

---

## 6. 复现步骤

```powershell
$env:JAVA_HOME = '<JAVA_HOME>'
Set-Location <REPO>\lab\fabric
.\gradlew.bat clean build --console=plain          # 4s，零警告
Copy-Item build\libs\modid-1.0.0.jar <MC_ROOT>\Server\mods\ -Force
Copy-Item <REPO>\lab\cache\fabric-api-0.163.0+26.3.jar <MC_ROOT>\Server\mods\ -Force

$p = Start-Process '<JAVA_EXE>' `
  -ArgumentList '-Xms1G','-Xmx2G','-Dmcdev.probe.port=39011','-jar','fabric-26.3.jar','nogui' `
  -WorkingDirectory '<MC_ROOT>\Server' -PassThru -WindowStyle Hidden

# 等日志出现 "Mcdev Probe listening on 127.0.0.1:39011" 后
curl.exe -s -H 'X-Mcdev-Token: mcdev' http://127.0.0.1:39011/status
```

**注意**：gradle 必须走代理（`-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=<PROXY_PORT>`）；`gradlew.bat` 的 `-D` 参数会被 PowerShell 吃掉，要用 `JAVA_TOOL_OPTIONS` 或 `GRADLE_OPTS` 传。
