# Minecraft 26.3 三家加载器真机陷阱（mc26-multiloader-traps）

> 这份清单里的每一条都是**在本套件自己的探针上跑出来的**，不是从文档抄的。26.3 时代的官方文档与
> 第三方指南普遍滞后（Forge 文档站的 gametest 页面还留着 1.21 的内容，`/en/26.3/` 直接 404），
> 所以这里给的是「现象 → 根因 → 处置」，能验证的地方都指到了源码行。
> 核对日期：2026-10（26.3 线）。版本会漂，表外版本先查官方 maven / meta 再动手。

## 1. 版本钉选（本套件真机构建通过的值）

| 项 | Fabric | Forge | NeoForge |
|---|---|---|---|
| MC | 26.3 | 26.3 | 26.3 |
| 加载器 | loader `0.19.5` | forge `26.3-66.0.13` | neo `26.3.0.88` |
| Gradle 插件 | `net.fabricmc.fabric-loom` `1.18-SNAPSHOT` | `net.minecraftforge.gradle` `[7.0.17,8)` | `net.neoforged.moddev` `2.0.148` |
| Java | toolchain 25 | toolchain 25 | toolchain 25 |
| 其他 | Fabric API `0.163.0+26.3` | `eventbus-validator:7.0.6`（annotationProcessor） | — |

- **26.1 起 Minecraft 不再混淆**：Fabric 用原生 `fabric-loom`（不是 `fabric-loom-remap`），`build.gradle`
  **不写 mappings 行**；mojmap 类名直接可用。照 1.x 时代模板抄 `mappings loom.officialMojangMappings()`
  会直接报错。
- **Java 25 是硬门槛**：三家都用 `java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }`。
  toolchain 走 foojay 自动下载，别改成 `sourceCompatibility`——环境 JDK 低于目标时那条路会报
  「不支持发行版本 25」且无法自举。
- **loom 1.18.x 要求 Gradle 自身跑在 Java 25 上**：`1.18-SNAPSHOT` 在本机（Gradle 用 JDK 25 起）通过；
  如果 Gradle 用 Java 22 起会报 `Dependency requires at least JVM runtime version 25`。这种情况退回 1.17 线。

## 2. 客户端 API：三条「看着对但不生效」

| 现象 | 26.3 的真相 | 处置 | 证据 |
|---|---|---|---|
| `minecraft.screen` 编译失败 | `Minecraft` 上确实没有 `screen` 字段了 | 改用 `mc.gui.screen()` / `mc.gui.setScreen(screen)` / `mc.gui.overlay()` | `lab/shared/src/client/java/com/mcdev/probe/ClientProbeServer.java:279` |
| `Window.getWindow()` 取 HWND 没了 | `Window.handle()` 返回 `long`，底层换成 **SDL**（`org.lwjgl.sdl.SDL_Event`，不再是 GLFW） | 需要窗口句柄就只能拿 `handle()` 的 long；要窗口事件就按 SDL 写 | `docs/p3-client-record.md:70` |
| `ClientInput.tick()` 调用后没反应 | 26.3 里它是**空实现**（javap 看到 `Code: 0: return`） | 不要靠 `tick()` 推进输入；直接操作输入状态或用 `ScriptedInput` 那套 | `lab/shared/src/client/java/com/mcdev/probe/action/ScriptedInput.java:13` |

- **`mc.gui.screen()` 是被误判过的点**：曾据「`Minecraft.screen` 被删」把 `/ui/*` 五条路由全部降级成
  501，后来实测 `Screen` 只是搬到了 `net.minecraft.client.gui.Gui`，五条路由全部可实现
  （`docs/milestone-2026-10-19-p0-p3.md:95`）。**结论：API 消失 ≠ 能力消失，先找搬家目标再判死刑。**

## 3. 静默失败：返回成功但什么都没发生

这一类是 26.3 排查成本最高的，因为日志干净、返回值正常。

1. **`GameType.byName(raw)` 未知输入静默回 `SURVIVAL`**
   （`GameTypes.java:11`，字节码实测见 `docs/p4a-action-layer-record.md` §12）。
   → 自己写显式枚举匹配，未知值回 400。别把 `byName` 当解析器。
2. **创造模式给物品的包服务端不处理**：26.3 Fabric 实测客户端 `hasInfiniteMaterials()=true`、
   服务端 `abilities.instabuild=1b`，服务端仍然不处理 `ServerboundSetCreativeModeSlotPacket`
   （官方入口与直发各试一次都不生效，`ClientActionBackend.java:668`）。
   → 换成 `/give` 或直接写背包，并明确告诉调用方「这条做不到」，别让上层以为拿到了物品。
3. **空气处 `pick` 无动作**：`handlePickItemFromBlock` 对没有可拾取方块的位置静默无动作
   （`ClientActionBackend.java:578`）→ 先读回判定，别只看请求是否发出。
4. **聊天有服务端限速**：默认 20 秒内的 spam 阈值，连发会被静默丢弃
   （`ClientProbeServer.java:1055`）→ 要断言聊天到达就上 `retry`，不要连发两条硬等。
5. **告示牌/命令方块编辑**：玩家距离、是否蜡封、是否有人在编辑——不满足时服务端**静默忽略**
   （`ClientProbeServer.java:1320`）；打开编辑器后还要等一个 tick 再发文本包，否则包被丢弃
   （`:1357`）→ 接口只能诚实报「包已发出」，判定靠回读。

## 4. 会断线的两个坑

- **`gamerule player_movement_check=true`（26.3 默认）下，客户端主动把位置挪远会被判非法移动直接断开**
  （`multiplayer.disconnect.invalid_player_movement`，`ClientActionBackend.java:277/290`）。
  测试服里关掉该 gamerule 后，服务端接受任意距离位移（实测 60 格一次到位）。**瞬移类场景必须先关它。**
- **`continueDestroyBlock` 会被客户端自己的命中校验拒掉**：视角没对准时表现为 `/action/mine` 超时、
  服务端一个 `START` 包都收不到（`ClientActionBackend.java:402`、`:1019`）→ 先 `aimAt` 把视线对上再挖。

## 5. 构建与环境

- **Forge 增量构建（[推断] 未在本仓单独实测）**：`runClient` 与 `build` 共用 `build/` 输出目录，
  改完 Java 不跑 `build` 直接 `runClient` 有可能仍用旧 class → 判「改了没生效」之前先全量构建一次，
  别凭一次现象下结论。
- **Forge EventBus 7 没有 `@Mod.EventBusSubscriber`**：订阅要显式往 bus 上挂
  （`docs/architecture.md:509`：客户端首个 tick 用 `ClientTickEvent.Post`）。
- **Fabric 探针对 fabric-api 是硬依赖**：`CommandRegistrationCallback` 来自 fabric-api，缺它会
  `HARD_DEP_NO_CANDIDATE modid 1.0.0 {depends fabric-api @ [*]}` 直接拒绝加载
  （`docs/p1-port-record.md:154`）。`fabric.mod.json` 的 `depends` 要写 `fabric-api`。
- **`white-list=true` 是 26.3 `server.properties` 的默认值**，而离线模式的白名单要写**离线 UUID**
  （`OfflinePlayer:名字` 的 MD5）→ 测试服直接 `white-list=false`。被踢的日志长这样：
  `Disconnecting <name>: You are not white-listed on this server!`
- **专用服不能用后台 job 起**：pwsh 后台 job 关闭 stdin，MC 服务端读到 EOF 立即退出（exit 1）
  （`docs/p3-client-record.md:162`）→ 用 `Start-Process` 脱离拉起。
- **`console.out.log` 被 java 独占锁住，且正文是 GBK**：读它要用共享读
  `[IO.File]::Open($p,'Open','Read','ReadWrite')` + `[Text.Encoding]::GetEncoding(936)` 解码
  （`docs/mc26.3-multi-loader-probe-feasibility.md:941`）。直接 `Get-Content` 会失败或乱码。
- **截图文��是异步落盘的**：刚拿到路径后有一小段时间 `length()` 还是 0（实测抓到 0 字节截图）
  （`ClientProbeServer.java:1014`）→ 读截图前先等文件非空。

## 6. 官方文档的坑

- `docs.minecraftforge.net` 的 `/en/latest/misc/gametest/` 仍写 `TestFunction` / `GameTestRegistry` /
  `@GameTestGenerator`，这些在 26.3 源码里**已完全移除**；`/en/26.3/` 404。
- 结论：26.3 相关的类名/签名，去源码或字节码（`javap`）确认，别信文档站快照。
