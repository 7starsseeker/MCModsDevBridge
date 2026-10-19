# 部署与实例登记（mcdev-instance-setup）

给「把套件装到一台新机器 / 新目录，并让它认得你已有的游戏实例」用的。命中词：部署、装到另一台、新环境、
没有服务端、路径写死、`instances/*.json`、登记实例、代理/镜像、离线、`provision-instance`、`register-instance`、
`install-probe`、`install-mod`、客户端怎么来、`client.mode`。

先接受一条边界：**套件本身不带任何 Minecraft 运行时**。这不是省事，是设计：

| 断言（`scripts/package.ps1` 里是真检查，不是自觉） | 为什么 |
|---|---|
| 出现非 `mcdev-probe-<v>-<loader>.jar` 的 `*.jar` ⇒ 打包失败 | 防止把被测 mod / 探针构建残留混进发布物 |
| 出现 `libraries/`、`assets/`、`versions/`、`runtime/`、`world/` ⇒ 打包失败 | 这些正是几百 MB 的来源 |
| 解压后 > 5 MB ⇒ 打包失败（当前实测 ≈ 0.9 MB） | 版本一变运行时全作废，套件只该有脚本 + 三份探针 jar + 文档 |

所以「部署」= ① 去取运行时 → ② 装探针 → ③ 登记一个指过去的实例。三步都只写自己的文件，不改你的游戏目录结构。

## 1. 要几套环境：分层，不要 18 套

不要预置「3 加载器 × 3 层 × 2 端」：

| 层 | 内容 | 常驻？ | 用途 |
|---|---|---|---|
| **L0 纯净** | 只装探针（Fabric 另加 fabric-api，硬依赖） | ✅ 每家一份 | 证明探针自己能跑；报 bug 时先在这里复现，排除第三方干扰 |
| **L1 目标** | 探针 + 被测 mod + 它的依赖 | ⏳ 按项目临时建 | 日常开发/回归，`devrun` 的默认目标 |
| **L2 生态** | 生产环境副本（几十上百个 mod） | 只留 Fabric 一份 | 抓与 carpet/architectury 这类「大范围改行为」的 mod 冲突 |

客户端侧**不需要常驻**：开发期用 `gradlew runClient`（改代码即生效），要验「用户视角」时才起独立客户端实例。

端口约定（新增实例按 +10 递增，自建 `256xx` / `391xx`）：

| 实例 | 加载器 | 游戏端口 | 服务端探针 |
|---|---|---|---|
| `fabric` / `forge` / `neoforge` | 各自 | 25566 / 25567 / 25568 | 39011 / 39012 / 39013 |
| `fabric-eco` | Fabric（L2） | 25569 | 39014 |

**客户端探针端口固定 39002**：一个客户端同时只能连一台服，所以不需要按实例分配。
资源预算：服务端 ≈ 550 MB/个，客户端 1–2 GB；三服 + 三端 ≈ 6–8 GB ⇒ **默认串行**（`-Family all` 是串行矩阵）。

## 2. 第一步：取运行时

```powershell
# 先探网络（不发请求内容，7 个端点 ≈ 11s；失败给可执行指引）
pwsh -NoProfile -File scripts\provision-instance.ps1 -Name probe -Loader fabric -Target $env:TEMP\x -CheckOnly

# a) 联网下载   b) 从已有安装复制（离线，最快）   c) 完全离线且没有现成服务端 ⇒ 在有网机器上跑 a/b，整目录带走
pwsh -NoProfile -File scripts\provision-instance.ps1 -Name dev-a -Loader fabric -Target <T>\dev-a -Download
pwsh -NoProfile -File scripts\provision-instance.ps1 -Name dev-a -Loader fabric -Target <T>\dev-a -CopyFrom <T>\Server -Offline
```

它顺带写好 `eula.txt`、`server.properties`（端口/离线模式/白名单/gamemode/level-name）、`run.ps1`，
并登记 `instances\dev-a.json`。参数：`-Proxy` / `-NoProxy` / `-Mirror` / `-McVersion`（默认 26.3）/
`-LoaderVersion`（默认 fabric 0.20.0 / forge 66.0.13 / neoforge 26.3.0.88）/ `-GamePort` / `-ProbePort` / `-JavaExe` / `-Force`。

> 注意 `provision-instance` 的 loader 默认值（0.20.0）是**服务端 launcher** 用的；mod 工程
> `gradle.properties` 里是另一套（loom 1.18-SNAPSHOT + loader 0.19.5）。两个别互抄。

## 3. 第二步：装探针（和被测 mod）

```powershell
scripts\install.ps1 -Bundle . -Loader fabric -Target <T>\dev-a          # 套件里的探针 jar
scripts\install-probe.ps1 -Loader fabric -Side server -Target <T>\dev-a # 单独装/重装探针（-Side client 装客户端那半）
scripts\remove-probe.ps1 -Target <T>\dev-a                              # 只删自己装过的
```

三个设计要点，照做即可：

1. **配置走文件，不靠 `-D`**：写 `<目标>\config\mcdev-probe.properties`（键与 `-D` 同名，`-D` 仍可临时覆盖）。
   理由：真实客户端/服务端由启动器拉起，不该为了装个探针去改启动器的 JVM 参数。
2. **写 `config\mcdev-probe.manifest.json`**（文件 + 原始 sha256 + 写过的键），卸载按它精确回滚。
   `client.mode=standalone` 启动前会校验这份 manifest 存在，缺了立刻报错而不是「等 3 分钟才发现连不上」。
3. **`-Side` 决定端口键**：`server` 写 `mcdev.probe.port`，`client` 写 `mcdev.probe.client.port`。
   `-Loader` 必须是**同族** jar，否则加载器根本不会加载它。

被测 mod 用 `install-mod.ps1`，不要用 `devrun -Mods` 做长跑：

```powershell
scripts\install-mod.ps1 -Jar build\libs\mymod.jar -Target <T>\dev-a -Side server
scripts\install-mod.ps1 -List -Target <T>\dev-a
scripts\install-mod.ps1 -Remove -Target <T>\dev-a
```

它把 sha256 记进 `config\mcdev-mods.manifest.json`；`-Remove` 只删「登记过且哈希对得上」的文件，
对不上会停下（`-Force` 才删）。原因很实际：`devrun -Mods` 是「跑一次顺手拷进去」，不记账也不清理，
连着换 case 会互相污染——上一轮的 mod 还在，这一轮就不知道是谁在起作用。

## 4. 第三步：登记实例（脚本不认识任何启动器）

编排脚本**不硬编码** `java -jar xxx.jar`，只读 `instances\<name>.json`：

```json
{
  "name": "fabric", "loader": "fabric", "dir": "<TARGET_DIR>\\Server",
  "gamePort": 25566, "probePort": 39011, "side": "both",
  "args": ["-Xmx2G", "-Dmcdev.probe.port=39011", "-jar", "fabric-26.3.jar", "nogui"],
  "javaExe": "<JAVA_HOME>\\bin\\java.exe",
  "client": { "mode": "gradle", "project": "lab\\fabric", "probePort": 39002 }
}
```

| 字段 | 说明 |
|---|---|
| `loader` | `fabric` / `forge` / `neoforge` |
| `dir` / `args` / `javaExe` | 服务端工作目录、完整启动参数、Java 路径（用登记里的，别依赖 PATH） |
| `side` | `both` / `server` / `client`：被测 mod 装在哪一侧，决定 `devrun` 起哪几端 |
| `client.mode` | `gradle` / `standalone` / `external`（见 §5）；纯服务端实例不用这个字段 |
| `proxy` / `noProxy` | 部署时定下的代理决策，之后 `devrun`/打包直接复用 |
| `computerUse` | 允许 GUI 兜底（截图看画面）时写 `"enabled"` |

用 `register-instance.ps1` 生成，不要手写：`-Name -Dir -Loader(auto) -GamePort -ProbePort -JavaExe -Side
-Client(gradle|standalone|external) -ClientProject -ClientRoot -ClientVersion -ClientGameDir -ClientLoader
-ClientProbePort(39002) -ClientUsername(mcdev) -Proxy -NoProxy -DshHome -Force`。
它**只读**游戏目录，唯一写入是 `instances\<name>.json`，默认不覆盖（要覆盖给 `-Force`）。
`-Dir` 里应该看到 `mods\`，服务端还应有 `eula.txt` / `server.properties`。

## 5. 客户端怎么来（三选一，写在 `client.mode`）

| mode | 谁启动 | 代价 / 前提 |
|---|---|---|
| `gradle` | 套件在你的 `lab\<loader>` 里跑 `runClient` | 改代码即时生效；需要 JDK + 完整源码树，最慢 |
| `standalone` | 套件按 `versions\<版本>\<版本>.json` 拼命令行 | **不下载任何东西**；缺库直接报错；首次 11–15 s 到主菜单（本机实测） |
| `external` | 你自己用任意启动器开 | 套件只等 `39002` 出现，不关心你怎么起的 |

`standalone` 三个字段：`client.root`（启动器根，含 `versions/`、`libraries/`、`assets/`）、
`client.version`（`versions/` 下的目录名）、`client.gameDir`（游戏目录，**与 root 分开**，免得污染你自己的 `.minecraft`）。
启动前会校验 `<gameDir>\config\mcdev-probe.manifest.json` 存在。
版本清单必须是**启动器合并后的自包含版本**：`Launch.ps1` 显式拒绝 `inheritsFrom`（只支持已展开的
`libraries` / `arguments`），占位符也只认固定那一批（含 `${library_directory}` / `${classpath_separator}`，
NeoForge 的清单会用到）。

两侧可以不是一个加载器：`client.loader` 单独写，实例 `loader` 只描述服务端（跨族组合是合法用法）。

## 6. 网络：先探，再决定要不要代理

7 个端点 ≈ 11 s，`-CheckOnly` 不发下载请求。本机实测（2026-10-20）结论：

- **只有 `maven.neoforged.net` 在这条线路上需要代理**（直连 10 s 超时）；其余直连可用。
- **`404 = 主机可达`**：探 HEAD 时 `piston-meta` / `libraries.minecraft.net` / `resources.download.minecraft.net` 返 404 属正常，别当失败。
- Fabric 可走镜像 `-Mirror https://bmclapi2.bangbang93.com`（与 `meta.fabricmc.net` 路径同构）。
- **Forge / NeoForge 安装器没有通用镜像**，只能代理或从别处整目录复制。

代理必须注入 **四处**，缺一处就卡在那一环：

| 消费者 | 怎么注入 |
|---|---|
| PowerShell 下载 | `-Proxy` / `HTTPS_PROXY` / 系统代理（脚本已处理） |
| **Java**（Forge/NeoForge 安装器、启动器） | `JAVA_TOOL_OPTIONS=-Dhttps.proxyHost=… -Dhttps.proxyPort=… -Dhttp.proxyHost=… -Dhttp.proxyPort=… -Dhttp.nonProxyHosts=localhost\|127.0.0.1` |
| **Gradle** | 同上（`gradlew` 子 JVM 会继承），或 `gradle.properties` 的 `systemProp.*` |
| 外部客户端启动器 | 启动器自己的「JVM 参数」加同样四行（只在拉 assets 时需要） |

`nonProxyHosts` 别省——否则探针自己也会走代理。

解析顺序（`scripts/lib/Env.ps1`）：`-Proxy` → 实例登记里的 `proxy` → `HTTPS_PROXY`/`HTTP_PROXY` →
Windows 系统代理（`HKCU\…\Internet Settings`，仅 `ProxyEnable=1` 时）→ 直连；`-NoProxy` 或 `noProxy: true` 强制直连。
`setup-wizard.ps1` 第 4 步会问一次并写进登记——**问一次比写死或靠猜都可靠**（端口因机器而异）。

## 7. 现场踩出来的坑

- **白名单必须写离线 UUID**：`online-mode=false` 时是 `<OFFLINE_UUID>` 这类
  由用户名算出的离线 UUID，填 Mojang UUID 会一直「You are not white-listed」。
- **Forge/NeoForge 客户端资源在 `%APPDATA%\.minecraft\assets`**（Mojang 布局，启动器从 `APPDATA` 推导）。
  离线部署可以从任意现成 `.minecraft/assets` 复制——实测 5,147 个对象，**缺一个就会在启动时卡住**。
- **首次启动的一次性界面**（无障碍向导 / 图形警告）由 `devrun` 在连接前关掉：不写 `options.txt`、不按坐标盲点。
- **`side: client` 的实例**（`dir` 为空、`gamePort` 为 0）是给单人世界测试用的，别拿它跑多人场景。
- **改完登记要重启 DSH** 才会被 `mcdev_devrun` 工具重新读到（工具启动时读一次 `mcdev-root.txt`）。

## 8. 这套件不管的事（别指望它）

- 不替你装加载器、不替你选启动器、不改你的 `server.properties` 语义（`provision` 只在新目录里写一份默认值）。
- 不做世界备份/回滚：`devrun` 会真在服务器上改方块、杀实体，**要保留的世界先备份**。
- 不判断 mod 该装哪一侧：`install-mod` 只在 jar 声明的 `environment` 与 `-Side` 冲突时**提醒**。
