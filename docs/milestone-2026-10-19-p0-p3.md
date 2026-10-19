# 里程碑：P0–P3 完成（2026-10-19）

> 这是**可以停下来、也可以交给别人接手**的一个状态点。
> 服务端缺口经三家加载器全通、客户端探针在 Fabric 上全通、构建零警告、文档齐备。
> 下一次开工请先读 [STATE.md](STATE.md)。

---

## 1. 这个里程碑证明了什么

原插件 `yan-mc/minecraft-mod-dev` 是一条 **DSH 插件 + MC 1.20.1 Forge 探针**的方案。
本项目要回答的问题是：这套思路能不能搬到 **MC 26.3 + Fabric / Forge / NeoForge 三家**。

**答案：能，而且比原版更简单。** 三条被实测确认的简化：

| 26.3 的新事实 | 消掉的原版复杂度 |
|---|---|
| Java Edition 26.1 起完全去混淆（26.3 客户端 jar 34,227 条目 / 10,737 个 `net/minecraft/*.class`，短名类 0 个） | `refmap` · `remap=false` · reobf · devJar 特例 —— 整类「静默失败坑」消失 |
| `MinecraftServer.tickRateManager()` → `ServerTickRateManager.setFrozen(boolean)` | 原探针唯一的 mixin（`ServerLevelMixin` 取消 `ServerLevel.tick`）**整份不需要** |
| `Gui.screen()` 提供当前 `Screen` | `/ui/*` 五条路由全部可实现（原先误判为「无实现路径」） |

最终形态：**共享库一份 + 三套加载器入口**。加载器耦合面只剩三样——入口类、FakePlayer、可选 mixin。

---

## 2. 交付物清单（全部已落盘）

### 2.1 代码

| 路径 | 行数 | 说明 |
|---|---|---|
| `lab/shared/src/main/java/com/mcdev/probe/ProbeServer.java` | 678 | 服务端 HTTP 探针，15 路由 + `route()` 注册表 + `/status.capabilities` |
| `lab/shared/src/client/java/com/mcdev/probe/ClientProbeServer.java` | 816 | 客户端 HTTP 探针，17 路由（含 5 条 `/ui/*` + 槽位表） |
| `lab/shared/src/main/java/com/mcdev/probe/RunRecorder.java` | 77 | `/run/dump` 快照落盘 |
| `lab/shared/src/main/java/com/mcdev/probe/ProbeLogs.java` | 71 | 日志捕获与 `/log/tail` |
| `lab/shared/src/main/java/com/mcdev/probe/ProbeCommands.java` | 60 | `/mcdev status｜freeze｜step` |
| `lab/shared/src/main/java/com/mcdev/probe/TickControl.java` | 60 | 包 `ServerTickRateManager`，零 mixin 冻结 |
| `lab/shared/src/main/java/com/mcdev/probe/ProbeLog.java` | 16 | slf4j logger |
| **共享库合计** | **1,778** | 三家共用一份源码（`srcDirs` 挂载，非复制） |

| 平台入口 | 行数 | 加载器机制 |
|---|---|---|
| `lab/fabric/src/main/java/.../McdevProbeFabric.java` | 54 | `DedicatedServerModInitializer` + `CommandRegistrationCallback` |
| `lab/forge/src/main/java/.../ForgeProbe.java` | 69 | EventBus 7：`XxxEvent.BUS.addListener(...)` |
| `lab/neoforge/src/main/java/.../NeoForgeProbe.java` | 67 | `NeoForge.EVENT_BUS.addListener(...)` |
| `lab/fabric/src/client/java/.../McdevProbeClient.java` | 32 | `ClientModInitializer` |

### 2.2 构建产物

| 骨架 | jar | 大小 | 警告 |
|---|---|---|---|
| Fabric | `modid-1.0.0.jar` | 51,321 B | 0 |
| Forge | `examplemod-1.0.0.jar` | 33,336 B | 0 |
| NeoForge | `examplemod-1.0.0.jar` | 37,459 B | 0 |

三份骨架均开启 `-Xlint:deprecation` + `deprecation = true`。

### 2.3 文档

| 文件 | 行数 | 内容 |
|---|---|---|
| [STATE.md](STATE.md) | 121 | **续做入口**：当前状态、下一步、环境坑、未决项 |
| [architecture.md](architecture.md) | 823 | 七条不变量、五条边界、能力模型、HTTP 契约、版本策略 |
| [mc26.3-multi-loader-probe-feasibility.md](mc26.3-multi-loader-probe-feasibility.md) | 1,148 | 可行性全量论证 + P0–P3 实测记录 |
| [p1-port-record.md](p1-port-record.md) | 176 | 17 条 API 迁移表、冻结语义 |
| [p2-adaptation-record.md](p2-adaptation-record.md) | 200 | 三家入口形状对照、EventBus 7 证据 |
| [p3-client-record.md](p3-client-record.md) | 254 | 端边界、客户端 API 迁移、`/ui/*` 更正 |

### 2.4 许可

`LICENSE`（Apache-2.0 官方全文，SHA256 `CFC7749B…3D30`）· `NOTICE`（`Copyright 2026 7starsseeker` + 上游 MIT 全文）· `THIRD_PARTY_NOTICES.md`（10 个第三方组件，含 NeoForge MDK = MIT）。

---

## 3. 验收证据（都是跑出来的，不是推断的）

| 项目 | 结果 |
|---|---|
| 三台专用服冷启动 | Fabric `Done (1.517s)` · Forge `Done (1.586s)` · NeoForge `Done (1.597s)` |
| 服务端 15 路由 × 3 家 | **12×200 + 3×501**（`/screenshot` `/ui/snapshot` `/ui/click` 在服务端按设计 501），0 失败 |
| 客户端 17 路由（Fabric） | **15×200 + 1×400（缺参）+ 1×409（无目标）**，**0 个 501** |
| 端到端连服 | `/connect` 后服务端 `/status` 的 `players` 从 0 → 1 |
| `/ui/*` 真实交互 | `/ui/close` 把 `AccessibilityOnboardingScreen` 关成 `TitleScreen`；`/ui/click` 把 `TitleScreen` 点成 `OptionsScreen`（14 个控件） |
| 槽位表 | `InventoryMenu` 46 格全量输出（`#0` 合成结果位、`#45` 副手 `containerSlot=40`） |
| GameTest（NeoForge） | `All 2 required tests passed :)`，退出码 0 |
| MSL 启动器 | 原样透传 `Core=@libraries/.../win_args.txt`，不二次加前缀 |
| 构建 | 三家 `clean build` 全绿，**0 警告 0 错误** |

---

## 4. 过程中被推翻的判断（值得单独记）

这些是花了时间才纠正过来的，写下来是为了别人不再踩一遍。

1. **`Minecraft.screen` 被删 ≠ `/ui/*` 做不了。** `Screen` 搬到了 `net.minecraft.client.gui.Gui`（`mc.gui.screen()`）。我一度据此把五条路由降级为 501，是错的。
2. **`/ui/click` 只调 `Screen.mouseClicked` 不够。** 它返回 `true` 但界面不动；真正触发的是 `AbstractWidget.onClick(MouseButtonEvent, boolean)`。**只看返回值会被骗。**
3. **Forge 的 `mods.toml` 里 `[[mixins]]` 是死配置。** 26.3 唯一生效通路是 jar MANIFEST 的 `MixinConfigs`；NeoForge 相反，`neoforge.mods.toml` 的 `[[mixins]]` 有效。
4. **"Class version 69 required is higher than …" 只是告警。** mixin 0.8.7 内部的 `MAX_SUPPORTED` 常量过时了，注入照样成功。
5. **`hosts` 钉 IP 对 Java 无效。** 解析层生效，但拦截点在 Java 的 TLS ClientHello。**只有本地代理（`127.0.0.1:<PROXY_PORT>`）管用**，且三行钉 IP 已移除。
6. **Forge 首次 build 静默退出不是 gradle 的问题。** 是 fork 出去的 mavenizer 下不动 GitHub 上的 Temurin 8 而挂死，失败不上报。从清华镜像预置后 4m44s 通过。
7. **Fabric 实装 loader 是 0.20.0，不是骨架里写的 0.19.5。** `ServerLauncher` 无视 stable 标志取最新。
8. **`System.out.println` 在 Forge/NeoForge 不进日志。** 探针不能拿 stdout 当执行证据，必须写文件或走 HTTP。

---

## 5. 这个里程碑**没有**做的事

明确列出来，避免被误读成「已完成」：

- **P4 未开工** —— 玩家操作全集（58 个 serverbound 包 → `PlayerAction` 抽象层），估 8–12 人日。
- **Forge / NeoForge 的客户端入口未落地** —— 形态已定（`@Mod` 构造器里判 `FMLEnvironment.dist == Dist.CLIENT`），但没写。
- **FakePlayer 未做** —— 按「角色操作在客户端实现」的决定降级为可选能力。
- **access widener / access transformer 未上** —— `hoveredSlot` 与容器原点仍读不到，`/ui/hover_slot` 只报「容器界面 + 槽位数 + 已派发 hover」。
- **DSH 插件层未写** —— 目前所有验证都是手工 curl，还没有 `mcdev_*` 工具。
- **多版本未做** —— 只支持 26.3；26.1.2 起是声明的范围，不是已实现的能力。
- **公开发布物料未做** —— 安装说明、分发形态、受众定位都还没定。

---

## 6. 下一步（按依赖排序）

| 顺序 | 事项 | 依赖 | 估时 |
|---|---|---|---|
| 1 | **P4 `PlayerAction` 抽象层** | 无 | 8–12 人日 |
| 2 | Forge / NeoForge 客户端入口 | P4 的客户端后端要先定形 | 1–2 人日 |
| 3 | access widener / access transformer（三家各验一次） | 与 P5 打包做 | 1 人日 |
| 4 | P5 三类 MOD 测试矩阵 T1–T5b | P4 | 3 人日 |
| 5 | P6 DSH 插件层（14 工具 + 加载器判定 + 多实例编排） | P4/P5 的接口稳定后 | 2–3 人日 |
| 6 | P7 文档与发布 | 全部 | 2 人日 |

**两个待决项已定**（用户 2026-10-19）：

| 决定 | 内容 | 落地动作 |
|---|---|---|
| ① `fabric-api` 依赖 | **留**。Fabric 生态里它事实上是标配，依赖它比自建 loader 原生命令注册便宜得多 | `fabric.mod.json` 写 `depends: { "fabric-api": "*" }`，缺了启动即报 `HARD_DEP_NO_CANDIDATE`（P1 已实测过这个报错形态）。注意 fabric-api **不在** loader 内，是独立 mod，必须显式声明 |
| ② 探针的产品形态 | **独立 mod，不是用户 MOD 的模块**。探针是开发期外挂组件，放进 `mods/` 用，开发完删掉，生产环境不该存在 | P5 打包前必须拆掉 P2 的临时脚手架：三个 `ExampleMod` 构造器里那行 `XxxProbe.install()` 要删，改成探针自己的 `@Mod("mcdev_probe")` |

② 的连锁影响：既然探针是独立 mod，那**探针不能读被测 MOD 的源码、也不能和被测 MOD 共享 classloader 假设**——两者在 `mods/` 里是两个 jar。测试时「装上探针 + 装上被测 MOD」是两次独立投放，正好也满足「探针自身声明为单端可选」这条约束。

---

## 7. 一句话交接

**骨架能起、探针能连、动作能驱动、结论有证据。** 剩下的是广度（P4 操作全集）和收口（P6 插件层、P7 发布），不是可行性问题。
