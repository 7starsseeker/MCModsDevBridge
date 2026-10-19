# 部署拓扑与网络形态（新环境部署手册）

> 2026-10-20 起定。适用范围：把本套件部署到**另一台机器**（或同一台机器的全新目录）上，
> 用它开发/测试 MC 26.3 的 mod。

## 0. 一条边界：套件不带运行时

发布的套件里**只有**三份探针 jar + 脚本 + DSH 插件 + 文档，**不含**任何 Minecraft 客户端、服务端、
加载器（Fabric/Forge/NeoForge）、资源文件。原因：一个 MC 服务端 + libraries 就上百 MB，
三个加载器各来一份会把套件变成几百 MB，而且版本一变就作废。

这条边界在 `scripts/package.ps1` 里是**断言**而不是自觉：

- 套件里出现非 `mcdev-probe-<v>-<loader>.jar` 的 `*.jar` ⇒ 打包失败；
- 出现 `libraries/`、`assets/`、`versions/`、`runtime/`、`world/` 目录 ⇒ 打包失败；
- 解压后超过 **5 MB** 护栏 ⇒ 打包失败（当前实测 ≈ 0.9 MB）。

运行时由部署脚本去取（见 §3），或在离线环境里从现成安装复制。

## 1. 实例分层（要几套环境）

**不要预置 3 加载器 × 3 层 × 2 端 = 18 套。** 层的作用不同，做法也不同：

| 层 | 内容 | 磁盘常驻？ | 用途 |
|---|---|---|---|
| **L0 纯净** | 只装探针（+ fabric-api，Fabric 硬依赖） | ✅ 常驻，每家一份 | 证明探针自身能跑；报 bug 时排除第三方干扰 |
| **L1 目标** | 探针 + **被测 mod** + 它的依赖 | ⏳ 按项目临时建 | 日常开发/回归（devrun 的默认目标） |
| **L2 生态** | 生产环境副本（96 mod 的 Fabric 实例） | ✅ 只保留 Fabric 一份 | 抓 mod 与 carpet/architectury 等的冲突 |

客户端侧同理，但**不需要常驻**：开发期用 `gradlew runClient`（改代码即生效），
验证「用户视角」时才需要独立客户端实例（见 §5）。

端口分配（沿用现有三台测试服，新增实例按 +10 递增）：

| 实例 | 加载器 | 游戏端口 | 探针端口 | 备注 |
|---|---|---|---|---|
| `fabric` | Fabric | 25566 | 39011 | L0/L1 |
| `forge` | Forge | 25567 | 39012 | L0/L1 |
| `neoforge` | NeoForge | 25568 | 39013 | L0/L1 |
| `fabric-eco` | Fabric | 25569 | 39014 | L2 生态，可延后 |
| 自建 `*` | 任意 | 256xx | 391xx | `provision-instance.ps1` 自动分配并登记 |

客户端探针端口固定 **39002**（一个客户端同时只能连一台服，所以不需要按实例分配）。

资源预算：一个 MC 服务端常驻约 **550 MB**，客户端 **1–2 GB**。三端 + 三服同时跑 ≈ 6–8 GB，
**串行跑**（`devrun -Family all`）是默认做法。

## 2. 启动器无关：实例登记

新环境的加载器/启动器/开服器**不可预知**，所以编排脚本不硬编码 `java -jar xxx.jar`，只读登记文件
`instances/<name>.json`：

```json
{
  "name": "fabric",
  "loader": "fabric",
  "dir": "<MC_ROOT>\\Server",
  "gamePort": 25566,
  "probePort": 39011,
  "side": "both",                       // both | server —— server 表示没人能连客户端（真·纯服务端）
  "start": { "exe": "<JAVA_EXE>",
             "args": ["-Xmx2G", "-Dmcdev.probe.port=39011", "-jar", "fabric-26.3.jar", "nogui"] },
  "client": { "mode": "gradle", "waitSec": 180 }   // gradle | external | none
}
```

`client.mode` 三选一：
- `gradle` —— 在本仓 `lab/<loader>` 里跑 `runClient`（改代码即生效，最慢）；
- `external` —— 外部启动的真实客户端实例，探针会监听本登记里的 `client.probePort`，devrun 只等它出现；
- `none` —— 纯服务端（用 `scenarios/server-only.json`，只做 `/command`、`/world/*`、观察类断言）。

## 3. 网络形态（本机实测，2026-10-20 22:56）

`scripts/provision-instance.ps1 -CheckOnly` 会先探测 7 个端点。**同一台机器，直连 vs 走本地代理**：

| 端点 | 用途 | 直连 | 走 `-Proxy http://127.0.0.1:<PROXY_PORT>` |
|---|---|---|---|
| meta.fabricmc.net | Fabric 版本元数据 | ✅ 200 | ✅ 200 |
| maven.fabricmc.net | Fabric loader/API 构件 | ✅ 200 | ✅ 200 |
| piston-meta.mojang.com | MC 版本清单 | ✅ 404（主机可达） | ✅ 404（主机可达） |
| libraries.minecraft.net | MC 运行库 | ✅ 404（主机可达） | ✅ 404（主机可达） |
| resources.download.minecraft.net | 客户端资源 | ✅ 404（主机可达） | ✅ 404（主机可达） |
| maven.minecraftforge.net | Forge 构件/安装器 | ✅ 200 | ✅ 200 |
| **maven.neoforged.net** | NeoForge 构件/安装器 | ❌ **10s 超时** | ✅ 200 |

结论：**只有 NeoForge 的 maven 在这条线路上需要代理**；其余直连可用。探测 7 个端点全程 ≈ 11 s，
失败也会给可执行指引（加 `-Proxy` 或改离线）。注意「404 = 主机可达」——探 HEAD 时 404 属正常。

代理解析顺序（`scripts/lib/Env.ps1`）：`-Proxy` → **实例登记里的 `proxy`**（部署时问过用户）→
`HTTPS_PROXY`/`HTTP_PROXY` 环境变量 → Windows 系统代理（`HKCU\...\Internet Settings`，仅 `ProxyEnable=1` 时）
→ 直连。`-NoProxy` 或登记里的 `noProxy: true` 强制直连。

**部署时先问一次代理，不要猜。** `scripts/setup-wizard.ps1` 第 4 步会问「直连 / 走代理（填地址）/ 用系统代理」，
答案写进 `instances\<名字>.json` 的 `proxy` / `noProxy`；之后 `devrun`、打包直接复用，不用再传 `-Proxy`。
理由：能从 GitHub 拿到套件的人通常本来就有代理，但端口因机器而异 —— 问一次比写死或靠猜都可靠。

### 3.1 代理要注入到四个地方（缺一个就卡在那一环）

| 消费者 | 注入方式 | 备注 |
|---|---|---|
| PowerShell 下载（`Invoke-WebRequest`） | `-Proxy` / `HTTPS_PROXY` / 系统代理 | 本脚本已处理 |
| **Java**（Forge/NeoForge 安装器、MC 启动器） | `JAVA_TOOL_OPTIONS=-Dhttps.proxyHost=… -Dhttps.proxyPort=… -Dhttp.proxyHost=… -Dhttp.proxyPort=… -Dhttp.nonProxyHosts=localhost\|127.0.0.1` | 安装器必须带，否则下载内核库时超时；`nonProxyHosts` 别省，否则探针自己也走代理 |
| **Gradle**（构建 mod、`runClient`） | `JAVA_TOOL_OPTIONS` 同上，或 `gradle.properties` 里 `systemProp.https.proxyHost=…` | 本仓一直用前者（`JAVA_TOOL_OPTIONS` 会被 gradlew 子 JVM 继承） |
| 外部客户端实例（HMCL/官方启动器） | 启动器自己的「JVM 参数」加同样四行 | 客户端拉资源（assets）时才需要 |

### 3.2 镜像（能少用一次代理就用）

- **Fabric**：`provision-instance.ps1 -Mirror https://bmclapi2.bangbang93.com`（路径与 `meta.fabricmc.net` 同构）。
- **Gradle 依赖**：`lab/*/build.gradle` 的 `repositories` 里可加阿里云 `https://maven.aliyun.com/repository/public`（本期未加，保持官方源；换环境时再加）。
- **Forge / NeoForge 安装器**：没有通用镜像（官方 maven 直发），**只能走代理**或离线复制。

## 4. 新环境部署 checklist

```powershell
# 0) 先决条件：JDK 25（探针编到 25）、PowerShell 7、Node（装 DSH 插件时才需要）
#    本机 JDK 25 在 <JAVA_HOME>

# 1) 网络探测（不下载任何东西；7 个端点 ≈ 11s）
pwsh -NoProfile -File scripts\provision-instance.ps1 -Name probe -Loader fabric -Target $env:TEMP\x -CheckOnly
#    有端点不通：加 -Proxy http://127.0.0.1:<port>，或改走离线

# 2) 取运行时 —— 三选一
#    a. 联网下载（Fabric 最快；Forge/NeoForge 走安装器，需要代理时加 -Proxy）
pwsh -NoProfile -File scripts\provision-instance.ps1 -Name dev-a -Loader fabric -Target <TARGET_DIR>\dev-a -Download
#    b. 离线：从已有安装复制（新环境里只要有任意一份同版本服务端）
pwsh -NoProfile -File scripts\provision-instance.ps1 -Name dev-a -Loader fabric -Target <TARGET_DIR>\dev-a -CopyFrom <TARGET_DIR>\Server -Offline
#    c. 完全离线且没有任何现成服务端 ⇒ 只能先在有网机器上跑一次 a/b，把目录整体带过去

# 3) 装探针 jar（套件里的三份，按加载器挑一份）
pwsh -NoProfile -File scripts\install.ps1 -Bundle . -Loader fabric -Target <TARGET_DIR>\dev-a

# 4) 冒烟（只服务端，不需要客户端）
pwsh -NoProfile -File scripts\devrun.ps1 -Instance dev-a -Scenario scenarios\server-only.json

# 5) 有客户端时跑完整场景
pwsh -NoProfile -File scripts\devrun.ps1 -Instance dev-a -Scenario scenarios\actions.json
```

`provision-instance.ps1` 会顺带写好：`eula.txt`、`server.properties`（端口/离线模式/白名单/gamemode/level-name）、
`run.ps1`（统一启动入口，devrun 可直接当 `start.exe` 用），并登记 `instances/<name>.json`。

## 5. 客户端怎么来（本期未做，需求已明确）

现状：客户端只能 `gradlew runClient`（首次 60–90 s，Forge 还要先把 assets 下载/拷满）。

要交付给**没有 gradle 工程**的人，需要独立客户端实例：
```
客户端目录/mods/mcdev_probe-1.0.0-<loader>.jar
启动器 JVM 参数：-Dmcdev.probe.client.port=39002 -Dmcdev.probe.token=mcdev
```
然后登记 `"client": { "mode": "external", "probePort": 39002, "waitSec": 600 }`。
收益：启动 15–25 s（vs 60–90 s），不需要 JDK/gradle 源码树。代价：改探针代码要重新打包。
**选择点（待用户定）**：HMCL profile / 套件自带手写启动壳 / 官方启动器 profile。

另注：Forge/NeoForge 的客户端资源落在 `%APPDATA%\.minecraft\assets`（Mojang 布局，
`slimelauncher` 取 `APPDATA` 推导），离线部署时可以从任意现成 `.minecraft/assets` 复制
（实测 5,147 个对象，缺一个都会在启动时卡住）。
