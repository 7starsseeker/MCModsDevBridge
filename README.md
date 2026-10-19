# MC Mods Dev Bridge

把「AI 写完 mod 只能靠人进游戏点一遍」变成一条可执行的端到端流水线：**起真实测试服 → 起真实客户端 → 连服 + op → 执行场景 JSON → 取证 → 收尾**，一次覆盖 **Fabric / Forge / NeoForge** 三家加载器。

目标 MC 版本 **26.3**，Java **25**，Windows。

> 这是**开发辅助套件**，不是给最终玩家用的 mod。它自带的探针 mod 只服务于「让 agent 能看见并操作真实游戏」。

---

## 它做什么

- **服务端探针**（`lab/shared/.../ProbeServer.java`）：状态、命令、方块 / NBT、掉落、日志、tick 控制、动作后端；方块读写可指定维度（`dim` 参数，未知维度回 400）。
- **客户端探针**（`lab/shared/.../ClientProbeServer.java` + `action/ClientActionBackend.java`）：UI 控件树、槽位、脚本化输入（点击 / 右键 / 按键）、截图、聊天命令、`/chat/tail`（读客户端**当前**看到的聊天与 actionbar，用来断言「玩家屏幕上真的出现了」）。
- **端到端编排**（`scripts/devrun.ps1`）：读实例登记 → 起服务端 → 等就绪 → 起客户端 → 连服并 op → 逐步执行场景 → 收集证据 → 收尾；失败退出码非 0。场景步骤支持 `retry`（等状态出现），断言支持等值 / 字段包含子串 / 响应体包含子串。
- **DSH 插件**（`tools/dsh-plugin/mcdev-devrun/`）：把同一套编排包成 `mcdev_devrun` / `mcdev_scenarios` 两个工具，供 agent 直接调用。

## 它不做什么

**它只提供能力，不提供「该测什么」的答案。**

- **不带针对某个 mod 的测试用例**：测哪些功能、断言什么文本、跑到多细，取决于你当时在开发什么。`scenarios/` 里的几个 JSON 是**示例与自检**，不是覆盖率承诺，也不是推荐清单。
- **不替代人自己上手**：音效、紫屏过渡动画、F3 读数、UI 手感、连招的爽快程度，这些没有数据通道，仍然要人进游戏看。套件的价值是把「能不能自动验」的那部分从人手里接过来，把人的时间留给只能靠人的那部分。
- **不替你判断「够了」**：场景跑绿只说明你写下的断言成立，不说明功能完整。

## 本仓库不含什么

**不包含任何服务端、客户端或加载器文件。** 它们由使用者在自己的机器上准备，再用向导登记：

- `instances/`（实例登记，含本机绝对路径）**不入库**，由 `scripts/setup-wizard.ps1` 或 `scripts/register-instance.ps1` 现场生成；
- `dist/`（打包产物）、`tmp/`、`lab/*/build/`、`lab/*/.gradle/` 同样不入库。

## 基于什么进一步开发

本仓库是在 **`yan-mc/minecraft-mod-dev`** 的基础上继续开发的，必须明确声明：

| 项 | 内容 |
|---|---|
| 上游工具 | [yan-mc/minecraft-mod-dev](https://github.com/yan-mc/minecraft-mod-dev) |
| 上游许可 | MIT，`Copyright (c) 2026 yan-mc`（全文转载在 [NOTICE](NOTICE)） |
| 从上游继承并改写 | `lab/shared/` + `lab/{fabric,forge,neoforge}/` 的探针 Java（源自上游 `lab/mcdev-probe/`）；`tools/dsh-plugin/`（源自上游 `plugin/minecraft-mod-dev/`） |
| 上游形态 | MC 1.20.1 + Forge 单一加载器 + Java 17；工具面为 `mcdev_build` / `mcdev_prepare` / `mcdev_ui` / `mcdev_click` 等 |
| 本仓库的差异 | MC 26.3；Fabric / Forge / NeoForge 三家；Java 25；把「起服 + 起客户端 + 连服 + 执行场景 + 取证」收敛成 `devrun` 一层编排，工具面为 `mcdev_devrun` / `mcdev_scenarios` |

除此之外：

- **三份构建骨架**分别生成自各自上游 MDK —— Fabric `fabric-example-mod`（CC0 1.0）、Forge MDK（LGPL 2.1）、NeoForge MDK（MIT），骨架内的 `LICENSE` / `LICENSE.txt` / `TEMPLATE_LICENSE.txt` 原样保留，**不并入**本仓库的 Apache-2.0；
- **DSH 插件层**面向 DeepSeek Harness 的公开插件 API 编写，DSH 本身及其各包保留自身许可证；
- 明细见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 快速开始

前置：JDK 25；一份自己装好的 MC 26.3 服务端目录（**含 `eula.txt` 与 `server.properties` 的那一层**）。
只做客户端时可以没有服务端目录 —— 实例登记里 `side: client` 就是这种形态。

```powershell
# 1) 登记一个实例（交互式向导，或直接用命令行）
pwsh -NoProfile -File scripts\setup-wizard.ps1
pwsh -NoProfile -File scripts\register-instance.ps1 -Name local -Dir '<你的服务端目录>' -Loader fabric

# 2) 只做环境检查（不起游戏）
pwsh -NoProfile -File scripts\devrun.ps1 -Family fabric -PreflightOnly

# 3) 跑一遍 smoke 场景
pwsh -NoProfile -File scripts\devrun.ps1 -Instance local -Scenario scenarios\smoke.json
```

装进 DSH（可选，装完需重启 DSH 使插件生效）：

```powershell
pwsh -NoProfile -File scripts\install-dsh-plugin.ps1 -Mode install
```

## 客户端怎么来

客户端有三种形态，选一种写进实例登记的 `client.mode`：

| `client.mode` | 谁启动客户端 | 适合 |
|---|---|---|
| `gradle` | 套件跑本仓 `lab\<loader>` 的 `runClient` | 在**本仓里**改探针/改 mod，改完即生效 |
| `standalone` | 套件按**启动器留下的版本清单**自己拼 java 命令行 | 套件使用者：手里只有一份装好的客户端，没有 gradle 工程 |
| `external` | 你自己开 | 任何启动器/任何形态，套件只等探针上线 |

`standalone` 是「启动器的最后一步」，不是另一个启动器：它读 `versions\<版本>\<版本>.json`（启动器合并后的自包含清单），
按 `rules` 过滤库、展开 `${...}` 占位符、拼出 classpath 与参数，然后起 java。**不下载任何东西**，缺库直接报错。

```powershell
# 装探针到客户端游戏目录（mods/、config/mcdev-probe.properties、安装记录 manifest）
pwsh -NoProfile -File scripts\install-probe.ps1 -Side client -Loader fabric -Target 'T:\你的客户端目录'
# 卸载（只删 manifest 登记过、且 sha256 没变的文件；.minecraft 里别的东西一律不碰）
pwsh -NoProfile -File scripts\remove-probe.ps1 -Target 'T:\你的客户端目录'

# 登记成实例：服务端 + 独立客户端
pwsh -NoProfile -File scripts\register-instance.ps1 -Name local -Dir '<服务端目录>' -Side both `
     -Client standalone -ClientRoot '<HMCL_HOME>' -ClientVersion '26.3-Fabric' `
     -ClientGameDir '<TARGET_DIR>\mcinst\client-fabric'
```

几个已经踩过的点：

- **客户端探针端口固定 39002** ⇒ 同一时刻只能有一个客户端在跑（`devrun` 开跑前会清掉占端口的旧进程）。
- `standalone` 起客户端前会校验 `<客户端目录>\config\mcdev-probe.manifest.json`：没有它直接报错并让你先跑 `install-probe.ps1`
  —— 「客户端起来了但探针没装」会表现成「等探针超时」，比直接报错贵得多。
- 首次启动客户端会先弹一次性界面（无障碍向导、图形警告……），`devrun` 会在连接前按界面自己的语义关掉它，
  **不写你的 `options.txt`**、不按坐标盲点、不认识的界面一律不碰。
- `side` 决定哪一端在场：`server`（只服）/ `client`（只客，没有服务端可起）/ `both`。场景里出现对端不存在的用例时，
  `devrun` 在**加载阶段**就报错，不会跑一半才发现。

### 环境解析约定

脚本不假设任何固定路径，`scripts/lib/Env.ps1` 统一解析：

| 变量 | 解析顺序 |
|---|---|
| java.exe | `-JavaExe` → `$env:JAVA_HOME\bin\java.exe` → `PATH` |
| 代理 | `-Proxy` → 实例登记里的 `proxy`（部署时问过你）→ `$env:HTTPS_PROXY` / `$env:HTTP_PROXY` → 注册表 IE 代理；`-NoProxy` 或登记里的 `noProxy: true` 强制直连 |

**代理在部署时问一次，不猜也不写死**：`setup-wizard.ps1` 第 4 步会问「直连 / 走代理（填地址）/ 用系统代理」，
答案落进 `instances\<名字>.json`（`proxy` / `noProxy`），之后 `devrun`、打包都直接复用。
能从这里拿到套件的人通常本来就有代理，但端口因机器而异 —— 问一次比靠猜可靠。

## 目录结构

| 路径 | 内容 |
|---|---|
| `lab/shared/` | 三家共用的探针实现（服务端 / 客户端 / 动作后端 / 命令） |
| `lab/fabric/` `lab/forge/` `lab/neoforge/` | 三家加载器的构建骨架与入口类 |
| `scripts/` | `devrun` 编排、实例登记、部署向导、探针装卸（`install-probe` / `remove-probe`）、打包、DSH 插件安装 |
| `scripts/lib/` | `Env.ps1`（java/代理解析）、`Launch.ps1`（按版本清单拼客户端命令行） |
| `scenarios/` | 场景 JSON（`smoke` / `server-only` / `actions` / `capabilities`；`capabilities` 是套件自身能力的自检，含跨维度读写、客户端聊天/actionbar 断言、日志子串断言） |
| `tools/dsh-plugin/` | DSH 插件（`mcdev_devrun` / `mcdev_scenarios`） |
| `docs/` | 可行性评估、逐阶段移植记录、部署拓扑、状态总览 |

先读 [docs/STATE.md](docs/STATE.md) 了解当前进度；[docs/architecture.md](docs/architecture.md) 讲整体设计；[docs/deployment-topology.md](docs/deployment-topology.md) 讲实例与端口分配。

## 许可

原创代码与文档以 **Apache License 2.0** 发布（见 [LICENSE](LICENSE) / [NOTICE](NOTICE)）；派生与骨架部分保留各自许可证，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

Minecraft 是 Mojang Synergies AB 的商标；本仓库不重新分发 Minecraft 客户端、服务端或任何游戏资源。
