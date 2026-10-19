# P4 步骤 0 记录：拆掉 P2 脚手架，探针成为独立 mod（2026-10-19）

> 目的：把「探针」从 P2 的临时形态（三份骨架都是 `examplemod`，靠宿主构造器里调 `install()`）
> 改成**产品形态**（探针自己就是 `@Mod("mcdev_probe")`，骨架里不再有被测 MOD）。
>
> 依据：用户 2026-10-19 的决定「探针是开发期外挂组件，不是用户 MOD 的一部分」
> （见 [STATE.md](STATE.md) §四条约束 之 4、[milestone-2026-10-19-p0-p3.md](milestone-2026-10-19-p0-p3.md) §6 决定②）。
> 用户原话要点：**先拆脚手架，再写新代码。**

---

## 0. 开工前的状态与风险处置

| 项 | 事实 |
|---|---|
| 工作区版本控制 | **无**。`<REPO>` 不是 git 仓库（`git status` → `fatal: not a git repository`）；`lab/forge` 也不是（`lab/fabric`、`lab/neoforge`、`ref-minecraft-mod-dev` 有 `.git`） |
| ⇒ 回滚手段 | 动手前手工备份：`tmp/backup-p4-step0-20261019-182555/`，**28 个文件 / 58,457 B**，覆盖 `lab/{fabric,forge,neoforge}` 的 `src/**` + `build.gradle` + `gradle.properties`（路径里的 `\` 换成 `__` 存平铺） |
| 提交 | 无（工作区无 git），所以「回滚」= 从上面这份备份里拷回去 |

---

## 1. 改了什么（逐条对照 STATE.md 的四行表）

| # | 原状 | 改后 |
|---|---|---|
| 1 | `lab/forge/.../com/example/examplemod/ExampleMod.java` 构造器里 `ForgeProbe.install()` | `ForgeProbe` 自己带 `@Mod(ForgeProbe.MODID)`，`MODID = "mcdev_probe"`，公开构造器 `public ForgeProbe()` 负责装（`@Mod` 构造器被加载器调用，装完即可） |
| 2 | 同上（neoforge） | `NeoForgeProbe` 自己带 `@Mod(NeoForgeProbe.MODID)`，`MODID = "mcdev_probe"`，构造器 `public NeoForgeProbe(IEventBus modEventBus)` 顺带 `modEventBus.addListener(NeoForgeGameTests::register)` —— GameTest 注册进入口类，正是原先宿主 `ExampleMod` 干的事 |
| 3 | `modid`（fabric.mod.json） / `examplemod`（forge/neoforge） | 三家统一 `mcdev_probe`；group 统一 `com.mcdev.probe` |
| 4 | `lab/{forge,neoforge}/src/main/java/com/example/examplemod/`（`Config.java`、`ExampleMod.java`、`ExampleModClient.java`、`NeoForgeGameTests.java`） | **整包删除**。`Config.java` / `ExampleModClient.java` 没有对等物，直接消失（探针没有配置项，客户端入口另有形态） |
| 5 | `NeoForgeGameTests`（在 `com.example.examplemod`） | 移到 `lab/neoforge/src/main/java/com/mcdev/probe/neoforge/NeoForgeGameTests.java`，包名 `com.mcdev.probe.neoforge`，GameTest 命名空间跟随 `mcdev_probe`（`mcdev_probe:p0_env` 环境 / `mcdev_probe:v6_p0_smoke` 用例） |
| 6 | fabric 的 `modid.mixins.json` / `modid.client.mixins.json` / `com/example/**` / `assets/modid/` | 全删（P1 起零 mixin）；图标挪到 `assets/mcdev_probe/icon.png`，`fabric.mod.json` 的 `icon` 指向它 |

**产物侧的连锁改动**（不写下来会被误当成「探针变胖了」）：

| 骨架 | 产物名 | 大小变化 |
|---|---|---|
| fabric | `modid-1.0.0.jar` → `mcdev_probe-1.0.0.jar` | 51,321 B → **47,852 B**（少了 example 的 class/lang/mixin 配置） |
| forge | `examplemod-1.0.0.jar` → `mcdev_probe-1.0.0.jar` | 33,336 B → **26,401 B** |
| neoforge | `examplemod-1.0.0.jar` → `mcdev_probe-1.0.0.jar` | 37,459 B → **29,960 B** |

jar 内清单已核对（`tar -tf`）：三家都只剩 `com/mcdev/probe/**` 与各自的 mod 元数据文件，
`com/example/**`、`examplemod` 字样在 **class 与资源里为 0 处**；剩余提及全部是
「P4 步骤 0 之前…」这类历史注释（有意保留，说明为什么曾经长那样）。

---

## 2. 验收证据（原始命令 + 原始输出）

### 2.1 三家干净重建（`JAVA_HOME=<JAVA_HOME>` + `JAVA_TOOL_OPTIONS` 走代理）

```
.\gradlew.bat --no-daemon --console=plain clean build
```

| 骨架 | 结果 | 抖动警告 |
|---|---|---|
| fabric | `BUILD SUCCESSFUL in 22s` | 0 javac warning |
| forge | `BUILD SUCCESSFUL in 20s` | 0 javac warning |
| neoforge | `BUILD SUCCESSFUL in 13s` | 0 javac warning |

（`--no-daemon` 是为了让「零警告」这条断言只由 javac 决定，不受 daemon 增量缓存影响。
neoforge 日志里那条 `You can use '--warning-mode all'` 是 **Gradle 自身的配置缓存弃用提示**，
不是 javac 警告；它在本项目 P0 阶段就存在。）

日志：`tmp/build-fabric-step0.log`、`tmp/build-forge-step0.log`、`tmp/build-neoforge-step0.log`。

### 2.2 部署 + 起服

| 骨架 | 测试服 | 探针端口 | 启动命令 |
|---|---|---|---|
| Fabric | `<MC_ROOT>\Server`（25566） | 39011 | `java -Xmx2G -Dmcdev.probe.port=39011 -jar fabric-26.3.jar nogui` |
| Forge | `<MC_ROOT>\Server1`（25567） | 39012 | `java -Dmcdev.probe.port=39012 @user_jvm_args.txt @libraries/net/minecraftforge/forge/26.3-66.0.13/win_args.txt nogui` |
| NeoForge | `<MC_ROOT>\Server2`（25568） | 39013 | `java -Dmcdev.probe.port=39013 @user_jvm_args.txt @libraries/net/neoforged/neoforge/26.3.0.88/win_args.txt nogui` |

三家都是 `Start-Process -WindowStyle Hidden -PassThru`（专用服不能用后台 job，见 STATE 环境表），
pid 落 `tmp/pids/{fabric,forge,neoforge}.pid`，停止走 `POST /command {"cmd":"stop"}`（实测 `result:0`，进程随即退出）。

**踩到一个真坑**：Fabric 第一次起服直接崩在模组加载：

```
模组 'MCDev Probe' (mcdev_probe) 1.0.0 需要 fabric-api 的 任意版本，但没有安装它
```

原因是 `fabric.mod.json` 声明了 `depends: {"fabric-api": "*"}`（用户 2026-10-19 定的「留」），
而三台测试服的 `mods/` 上一轮结束后已被清空（只剩探针 jar）。修法：把
`<MC_ROOT>\Server-26.3\mods\fabric-api-0.163.0+26.3.jar` 拷进 `Server\mods\`。
—— 这正好证明「缺依赖启动即报 `HARD_DEP_NO_CANDIDATE`」这条设计是生效的，不是纸面承诺。

### 2.3 15 路由矩阵（`tmp/p4s0-matrix.ps1`，`X-Mcdev-Token: mcdev`）

| 骨架 | `/status.data.loader` | 矩阵结果 |
|---|---|---|
| Fabric | `fabric` | `SUMMARY: 200=12 501=3 other=0 total=15` |
| Forge | `forge` | `SUMMARY: 200=12 501=3 other=0 total=15` |
| NeoForge | `neoforge` | `SUMMARY: 200=12 501=3 other=0 total=15` |

501 的三条仍是按设计的 `/screenshot` `/ui/snapshot` `/ui/click`（`client-only endpoint; this probe is running on the server`）。
与 P2 基线完全一致 ⇒ **去脚手架没有改变任何 HTTP 行为**。

NeoForge 的 mod 列表里可见 `MCDev Probe 1.0.0 (mcdev_probe)` + `mcdev_probe (jar(mods/mcdev_probe-1.0.0.jar))`。

### 2.4 两个「不是本次改动引入」的启动噪声（记录在案，避免下次误判）

1. **`examplemod (version 1.0.0 -> MISSING)` + `Unidentified mapping from registry minecraft:block: examplemod:example_block: 1286`**
   —— 来自**世界存档**：`<MC_ROOT>\Server1\testworld` 是在 examplemod 还存在的时期生成的，
   Forge 把注册表 ID 映射写进了世界数据。拆掉 examplemod 后这份映射悬空。
   **处置：不删世界、不手改 `level.dat`**（删世界不可逆，且会一并丢掉 P0–P3 的测试痕迹）。
   影响仅限启动时两条 WARN/ERROR；要清掉就删 `testworld` 重新生成 —— 留给用户决定。
2. **`io.netty.channel.kqueue/epoll.Native` 的 `NoClassDefFoundError`（Forge/NeoForge 的 `DebugFile` appender）**
   —— 在 P2 的 `p2v-forge.err.log` 里逐字存在，是 26.3 Forge 自己的 log4j appender 在 Windows 上
   探测原生传输的噪声，**与探针无关**，本次未新增。

另外：`p4s0-*.out` 里中文变成 `����Ѽ���` 是**重定向文件的编码**问题（MC 控制台 appender 按平台编码写
文件，我这里重定向到 `-RedirectStandardOutput`），不是 mod 的问题 —— 走 `/log/tail` 读内存环形缓冲
拿到的是正常 UTF-8 字符串（P2 已确认）。不修：要修得给 JVM 加 `-Dfile.encoding=UTF-8`，
那是服务器启动参数、不是产品行为。

---

## 3. 没做 / 明确留白

| 项 | 为什么 |
|---|---|
| Forge / NeoForge 的**客户端**入口 | 形态已定（`@Mod` 构造器里判 `FMLEnvironment.dist == Dist.CLIENT`），但落地依赖「客户端动作后端先定形」（里程碑 §6 依赖顺序 2）。本次只搬服务端入口，避免写出一个没有动作层可用的空壳客户端入口 |
| NeoForge GameTest **重跑** | `NeoForgeGameTests` 被搬了包，服务端启动能证明 `RegisterGameTestsEvent` 监听器装上了（否则 mod 构造期就炸），但 `mcdev_probe:p0_env` 这两条用例没有重新跑 `runGameTestServer`。留待 P5 测试矩阵一起跑 |
| Fabric 客户端冒烟 | 同上：属于 P4 主体（客户端动作层）的验证范围 |
| 世界存档清理 | 见 §2.4 第 1 条，不可逆动作留给用户 |

---

## 4. 回滚办法（三步，全部可逆）

```powershell
# 1) 备份里拷回源码与构建脚本（\ → __ 的平铺命名要还原）
#    备份目录：tmp/backup-p4-step0-20261019-182555/
# 2) 三家 clean build
# 3) jar 拷回 <MC_ROOT>\Server{,1,2}\mods\
```

**注意**：回滚会把 `mcdev_probe` 变回 `examplemod`/`modid`，届时 `mods/` 里要同时放回
`fabric-api`（fabric 侧）——它现在已经在 `Server\mods\` 里了，不必再动。
