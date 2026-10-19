# P2 三套适配 —— 实测记录

> 日期：2026-10-19 ｜ 目标加载器：Fabric 0.19.5+ / Forge 26.3-66.0.13 / NeoForge 26.3.0.88 ｜ MC 26.3 ｜ Java 25
>
> 本文件记录 P2 的**验收证据**：三家 `/status` 的 `side` 与 `loader` 字段、`/command` 生效、编译零警告。
> 上游：`docs/mc26.3-multi-loader-probe-feasibility.md`（可行性）、`docs/architecture.md`（架构）、`docs/p1-port-record.md`（P1 共享库移植）。

---

## 0. 一句话结论

**三家适配面收敛到「一个 40 行的入口类」，共享库只加了 19 行（`loader` 标识，650→669）。**

三家的 `/status` 都返回 `side=server` + 各自的 `loader` 名，15 条路由矩阵与 P1（Fabric）完全一致：
12 条 2xx + 3 条 501（`/screenshot` `/ui/snapshot` `/ui/click` 是服务端设计上的 client-only）。
三份骨架 `clean build` 全绿、**零警告**。

---

## 1. 三家入口的实际形状（这是 P2 唯一真正的新代码）

三个入口类的共同设计：**没有 `@Mod` / `@Mod` 注解不在探针身上**，宿主 mod 在构造器里调一次
`install()`。好处是探针可以作为「模块」塞进任意已有 mod，不需要用户为自己的 mod 改主类。
（见 §5 的例外说明 —— 最终产品形态会反过来。）

| | Fabric | Forge | NeoForge |
|---|---|---|---|
| 入口接口 | `DedicatedServerModInitializer` | 构造函数注入 `FMLJavaModLoadingContext` | 构造函数注入 `IEventBus, ModContainer` |
| 命令注册 | `CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> …)` | `RegisterCommandsEvent.BUS.addListener(e -> e.getDispatcher())` | `NeoForge.EVENT_BUS.addListener(e -> e.getDispatcher())` |
| 启动事件 | `ServerLifecycleEvents.SERVER_STARTED` | `ServerStartedEvent.BUS` | `NeoForge.EVENT_BUS` + `ServerStartedEvent` |
| 停止事件 | `ServerLifecycleEvents.SERVER_STOPPING` | `ServerStoppingEvent.BUS` | `NeoForge.EVENT_BUS` + `ServerStoppingEvent` |
| 端判断 | 接口本身只在专用服跑（`server` entrypoint） | `FMLEnvironment.dist != Dist.DEDICATED_SERVER` | `FMLEnvironment.getDist() != Dist.DEDICATED_SERVER` |
| `Dist` 包名 | — | `net.minecraftforge.api.distmarker.Dist` | `net.neoforged.api.distmarker.Dist` |

### 1.1 Forge 26.3 的事件模型与 1.20.1 完全不同（EventBus 7）

原探针的 `MinecraftForge.EVENT_BUS.register(this)` 在 26.3 **连名字都对不上**：

- `MinecraftForge.EVENT_BUS` 现在是一个 `EventBusMigrationHelper` 实例（迁移壳），不是总线；
- 每个事件类自己带一个静态 `BUS` 字段，用法是 `XxxEvent.BUS.addListener(Consumer<T>)`；
- `FMLJavaModLoadingContext.getModBusGroup()` 返回的 `BusGroup` 是 **mod 生命周期总线**，
  不是游戏事件总线 —— 游戏事件不需要它。

```
javap net.minecraftforge.event.server.ServerStartedEvent
→ public final class … extends java.lang.Record implements ServerLifecycleEvent
    public static final EventBus<ServerStartedEvent> BUS;
    public MinecraftServer getServer();

javap net.minecraftforge.eventbus.api.bus.EventBus
→ EventListener addListener(java.util.function.Consumer<T>)
```

### 1.2 NeoForge 26.3 反而是「传统」形状

`NeoForge.EVENT_BUS` 就是 `IEventBus`（eventbus 8.0.5），`addListener(Consumer<T>)` 与现代
NeoForge 教程一致。入口用构造器注入 `(IEventBus modEventBus, ModContainer modContainer)`。

---

## 2. 共享库的唯一改动：`loader` 标识（+19 行）

`/status` 原本只有 `side`（固定 `"server"`）。这不足以回答「我连上的是哪一份 jar」——
DSH 插件层有三个并行实例，端口是唯一的区分手段，而端口是最容易配错的。

但**共享库不能自己判断加载器**（判断需要加载器 API，违反架构 §I1）。所以：

```java
// ProbeServer.java
private static String loader = "unknown";

public static synchronized void setLoader(String name) {
    loader = name == null || name.isEmpty() ? "unknown" : name;
}

// handleStatus:
data.put("loader", loader);
```

平台入口在 `start()` **之前**调一次 `setLoader("forge")`。放在之后会留一个「监听已开、标志未写」
的微秒级窗口，没有理由留它。

---

## 3. 验收证据

### 3.1 构建：三份骨架 `clean build` 全绿、零警告

三家 `build.gradle` 统一开了 `-Xlint:deprecation` + `deprecation = true`，
用 `grep '警告|warning:|注意:|Note:'` 过滤构建日志 = **0 行命中**。

| 加载器 | 结果 | 产物 | 大小 |
|---|---|---|---|
| Fabric | BUILD SUCCESSFUL | `lab/fabric/build/libs/modid-1.0.0.jar` | 33,282 B |
| Forge | BUILD SUCCESSFUL | `lab/forge/build/libs/examplemod-1.0.0.jar` | 33,336 B |
| NeoForge | BUILD SUCCESSFUL | `lab/neoforge/build/libs/examplemod-1.0.0.jar` | 37,459 B |

### 3.2 运行：三家的 `/status`

```
[fabric]   {"ok":true,"data":{"side":"server","loader":"fabric",  "tick":14,"gameTime":14,  "dayTime":14,  "players":0,"maxPlayers":8,"level":"testworld","version":"26.3","frozen":false,"stepBudget":0}}
[forge]    {"ok":true,"data":{"side":"server","loader":"forge",   "tick":2, "gameTime":1201,"dayTime":1201,"players":0,"maxPlayers":8,"level":"testworld","version":"26.3","frozen":false,"stepBudget":0}}
[neoforge] {"ok":true,"data":{"side":"server","loader":"neoforge","tick":17,"gameTime":1216,"dayTime":1216,"players":0,"maxPlayers":8,"level":"testworld","version":"26.3","frozen":false,"stepBudget":0}}
```

`/command`（`{"cmd":"list"}`）三家都返回 `{"ok":true,"data":{"cmd":"list","result":0}}`。

> `tick` 与 `gameTime` 的差值不是 bug：`tick` 是本次进程启动后的 tick 数，
> `gameTime` 是世界存档里已累积的游戏时间（三台 `testworld` 都跑过多次）。

### 3.3 运行：15 条路由矩阵（Forge / NeoForge，Fabric 见 P1 记录）

| 路由 | 结果 | 备注 |
|---|---|---|
| `/status` | 200 | `side=server` + `loader` |
| `/log/tail?n=3` | 200 | log4j ring buffer |
| `/command`（POST `list`） | 200 | `result:0` |
| `/world/block?x=0&y=64&z=0` | 200 | `block:"minecraft:air"` |
| `/world/break?x=1&y=64&z=0` | 200 | `removed:false`（目标本来就是空气） |
| `/world/mine?…&player=Dev` | 200 | `error:"player not online"` ← 假玩家属 P3/P4 |
| `/items?x=0&y=64&z=0&r=2` | 200 | `total:0` |
| `/craft?recipe=minecraft:stick&player=Dev` | 200 | `error:"player not online"` ← 同上 |
| `/recipe?id=minecraft:stick` | 200 | `found:true, item:minecraft:stick, count:4` |
| `/loot?block=minecraft:stone&…` | 200 | `drops:[{item:minecraft:cobblestone,count:1}]` |
| `/item?item=minecraft:stone` | 200 | 取到贴图元数据（`visible/width/color`） |
| `/run/dump?n=5` | 200 | 落盘 `.mcdev/runs/<id>/` |
| `/screenshot` | **501** | `client-only endpoint; this probe is running on the server` |
| `/ui/snapshot` | **501** | 同上 |
| `/ui/click` | **501** | 同上 |

**汇总：12 × 200 + 3 × 501 = 15，无失败项。** 三家完全一致。

### 3.4 日志干净度

三家日志 `grep 'ERROR|Exception'` 后排除 `OFFLINE/INSECURE/hackers`（`online-mode=false` 的
固有警告）⇒ **零命中**。

---

## 4. 清理掉的 P0 遗留（不清理会直接崩启动）

P0 的 V7 试验留下了 `mcdev.mixins.json`（`required:true` + `defaultRequire:1`），
它注入的 `com.mcdev.shared.mixin.ServerRunMixin` 在 P1 已经随 `TickRateManager` 改造删除。
**json 还在、类没了** ⇒ 启动期 mixin 加载失败、`required:true` 直接崩。

已删：`lab/forge/src/main/resources/mcdev.mixins.json`、`lab/neoforge/src/main/resources/mcdev.mixins.json`；
同时清掉两处声明（`lab/forge/build.gradle` 的 jar `MixinConfigs`、`neoforge.mods.toml` 的 `[[mixins]]`），
原地留注释说明「将来真需要时的唯一生效通路分别是什么」（Forge = MANIFEST，NeoForge = toml）。

> 这条正是架构 §I7「一个功能的改动落点必须局部化」的反面教材：P1 删了类，P0 的配置还留着。

---

## 5. 一个必须现在说清的设计偏差：骨架里探针是「模块」，产品里应当是「独立 mod」

P2 的实现把平台入口做成**不带 `@Mod`** 的类，由宿主的 `ExampleMod` 构造器调 `install()`。
这是被骨架结构逼出来的：**一个 gradle 项目 = 一个 mod id**，两个 `@Mod` 冲突。

但**最终产品形态应该反过来** —— 探针是**自己的 mod**（自己的 mod id、自己的 `mods.toml` /
`fabric.mod.json`），用户把它丢进 `mods/` 就完事，**不需要改自己 mod 的任何一行代码**。

这两件事不冲突，因为 `install()` 的形状不变，差别只有：
- 入口类加 `@Mod("mcdev_probe")`（Forge/NeoForge）
- 独立一份元数据文件
- 不再从 `ExampleMod` 调 `install()`，改为在探针自己的 `@Mod` 构造器里调

P5（打包发布）做这件事。**P2 的验收不受影响** —— 三家都通了，且通的是同一份共享库。

---

## 6. 环境备注（换机复现要知道的）

- **Java**：`<JAVA_EXE>` = Temurin 25.0.4.1+1 LTS。
- **gradle 必须走本地代理**：`JAVA_TOOL_OPTIONS=-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=<PROXY_PORT> -Dhttp.proxyHost=…`。
  Java 的 TLS ClientHello 到 `maven.neoforged.net` 会被掐（curl/.NET 正常），hosts 钉 IP 无效。
- **Fabric 的 fabric-api 在测试实例里是从 `.fabric/processedMods/` 缓存解析的**：
  清空 `mods/` 后启动，日志仍报 `Loading 43 mods: - fabric-api 0.163.0+26.3`。
  → 这是**测试实例的历史缓存**，不是产品行为。产品必须把 `fabric-api` 写进 `fabric.mod.json`
  的 `depends`，由用户自行装进 `mods/`。
- **读日志**：`console.out.log` / 重定向日志被 java 独占，要用
  `[IO.File]::Open($p,'Open','Read','ReadWrite')`；内容通常是 **GBK(cp936)**，
  必须 `[Text.Encoding]::GetEncoding(936)` 解码。控制台里中文变成 `鍏ュ彛宸插姞杞?` 就是漏了这一步。
- 三台测试服端口：Fabric 25566 / Forge 25567 / NeoForge 25568；探针 39011 / 39012 / 39013。
- **生产服 `Server-26.3`（25565）全程未启动、未改动。**

---

## 7. 与 P1 的差异总结（给 P3 的输入）

| 项 | P1（Fabric） | P2（+Forge/NeoForge） |
|---|---|---|
| 新增 Java | 共享库 934 行 + Fabric 入口 | 两个入口类：Forge 69 行 / NeoForge 67 行 |
| 共享库改动 | — | `loader` 字段 + `setLoader()`，19 行 |
| 加载器 API 出现位置 | 1 个文件 | 3 个文件（`platform/<loader>/` 各一） |
| 路由可用性 | 15/15 | 15/15，三家一致 |
| 编译警告 | 0 | 0 |
| mixin 需求 | 0 | 0 |

**结论：三套适配的边际成本 = 2 个入口类（69 + 67 行）+ 1 个 19 行的共享库字段（934 → 953 行）。**
这正是架构里「加载器边界只留四样接缝」的设计意图 —— 第四样（FakePlayer）留到 P3/P4。
