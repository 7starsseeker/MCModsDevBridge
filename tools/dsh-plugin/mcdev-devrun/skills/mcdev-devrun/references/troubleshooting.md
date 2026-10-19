# 红了怎么定因（mcdev-devrun / 探针）

> 顺序：先 `-PreflightOnly`（登记/端口/jar/Java），再看 `server.err` 与 `client.out` 尾部，
> 最后看 `results.json` 是哪一步开始红的。下面按「看到的症状」索引。

| 症状 | 最可能的原因 | 处置 |
|---|---|---|
| 客户端一直卡在「连接中」，永远进不了世界 | 调接口时把 host/port 放进了 body；`/connect` **只认 query**，body 被静默忽略 → 连到默认 25566 | 改 `POST /connect?host=...&port=...` |
| 日志里 `Disconnecting <name>: You are not white-listed on this server!` | 26.3 `server.properties` 默认 `white-list=true`，而离线模式白名单要写**离线 UUID**（`OfflinePlayer:名字` 的 MD5） | 测试服 `white-list=false`；`devrun` 超时输出会自动抄出断线原因并单独提示这条 |
| 服务端进程起来就 exit 1，日志一个字都没有 | 用后台 job 起的：pwsh 后台 job 关 stdin，MC 读到 EOF 立即退出 | 用 `Start-Process` 脱离拉起（`devrun.ps1` 已这么做） |
| 读 `console.out.log` 报占用 / 满屏乱码 | 文件被 java 独占锁住，且正文是 GBK(cp936) | 共享读 `[IO.File]::Open($p,'Open','Read','ReadWrite')` + `[Encoding]::GetEncoding(936)` |
| 探针**5 秒超时**，但同一条请求手工 curl / PowerShell 打过去 49ms 就成功 | 系统 HTTP 代理把 localhost 的 POST 转发出去然后卡住（实测踩过） | `-NoProxy`，或让 java 侧放行 localhost（`lib\Env.ps1` 的默认行为） |
| Fabric 启动即报 `HARD_DEP_NO_CANDIDATE modid 1.0.0 {depends fabric-api @ [*]}` | 探针对 fabric-api 是硬依赖（命令注册来自 fabric-api），而它不会被一起装 | 把对应版本的 fabric-api jar 放进 `mods\` |
| mods 里明明有探针，`/status` 却拒绝连接 | jar 与加载器不匹配（三个加载器三个 jar，互相不认） | 装对应 `mcdev-probe-<family>-<version>.jar` |
| `Dependency requires at least JVM runtime version 25` | loom 1.18.x 要求 **Gradle 自身**跑在 Java 25 上，不是只要求 toolchain | 用 JDK 25 起 Gradle，或退回 loom 1.17 线 |
| `/action/mine` 超时，服务端一个 START 包都没收到 | 客户端命中校验没过（视角没对准），或位移被判非法 | 先 `aimAt` 对准；瞬移类场景先关 `gamerule player_movement_check` |
| 玩家被踢，日志 `multiplayer.disconnect.invalid_player_movement` | 26.3 默认 `player_movement_check=true`，客户端自己挪远会被判非法移动 | 测试服关掉该 gamerule（实测关掉后 60 格一次到位） |
| 截图文件 0 字节 | 截图是**异步落盘**，拿到路径 ≠ 写完 | 读之前轮询到 `length() > 0` |
| 矩阵跑第二个用例就端口冲突 | 客户端探针端口三家共用 39002，同一时刻只能有一个客户端 | 串行（`devrun-matrix.ps1` 本来就是串行）；先确认上一轮客户端真的退了 |
| 改了 Java 代码但行为没变（Forge） | [推断] `runClient` 与 `build` 共用输出目录，不重新构建可能还用旧 class | 判「没生效」前先全量构建一次 |
| `ok:true` 但什么都没发生 | 静默失败（26.3 有多条：`GameType.byName`、创造模式发物品包、空气处 pick、聊天限速…） | 见 `mc26-multiloader-traps` §3，判定一律回读 |
| 打开已有单人世界后一直进不去，`/status` 的 `screen` 是 `BackupConfirmScreen` | 26.3 打开「已有」存档要你先选「备份再进 / 跳过直接进」；探针默认 `backup=ask` 什么都不点 | 场景里写 `&backup=skip`（或 `do`）；`devrun` 会把这个问题问出来并给三个选项 |
| 探针拒绝连接，但进程明明在监听 | 探针要 `X-Mcdev-Token`（默认 `mcdev`） | **401 ≠ 没起来**；带 `X-Mcdev-Token: mcdev` 再试 |
| standalone 客户端启动报参数异常 / 占位符残留 | 版本清单不是启动器合并后的自包含清单 | `Launch.ps1` 只认自包含清单（显式拒绝 `inheritsFrom`），占位符只认固定那一批 |
| `install-probe -Side client` 报「mods 里没有 fabric-api」 | 它只检查、不下载 | 先把 fabric-api 放进客户端 `mods\` |

## 检查顺序（照做能省很多时间）

1. `devrun.ps1 -Instance <名> -PreflightOnly` —— 登记解析、端口占用、探针 jar、Java 路径，一次全打印。
2. 探针单点：`GET /status` 两侧都打得到吗？打不到是**进程/端口/jar**问题，不是场景问题。
3. `/status` 通了但场景红：看 `results.json` 里第一条红的步骤，把它的**原始响应体**读出来
   —— 多数情况答案就在 body 里（`error.code` + `message`）。
4. 服务端日志尾部（`GET /log/tail?n=80` 或 `server.err`）—— 拒绝加载、白名单、断线原因都在这里。
5. 还是看不懂才去读脚本源码：`scripts/devrun.ps1` 的六段注释就是它自己的说明书。
