# MC Mods Dev Bridge

把「AI 写完 mod 只能靠人进游戏点一遍」变成一条可执行的端到端流水线：**起真实测试服 → 起真实客户端 → 连服 + op → 执行场景 JSON → 取证 → 收尾**，一次覆盖 **Fabric / Forge / NeoForge** 三家加载器。

目标 MC 版本 **26.3**，Java **25**，Windows。

> 这是**开发辅助套件**，不是给最终玩家用的 mod。它自带的探针 mod 只服务于「让 agent 能看见并操作真实游戏」。

---

## 它做什么

- **服务端探针**（`lab/shared/.../ProbeServer.java`）：状态、命令、方块 / NBT、掉落、日志、tick 控制、动作后端；方块读写可指定维度（`dim` 参数，未知维度回 400）。
- **客户端探针**（`lab/shared/.../ClientProbeServer.java` + `action/ClientActionBackend.java`）：UI 控件树、槽位、脚本化输入（点击 / 右键 / 按键）、截图、聊天命令、`/chat/tail`（读客户端**当前**看到的聊天与 actionbar，用来断言「玩家屏幕上真的出现了」）。
- **端到端编排**（`scripts/devrun.ps1`）：读实例登记 → 起服务端 → 等就绪 → 起客户端 → 连服并 op → 逐步执行场景 → 收集证据 → 收尾；失败退出码非 0。场景步骤支持 `retry`（等状态出现），断言支持等值 / 字段包含子串 / 字段**不**包含子串（`notContains`）/ 响应体包含子串（`text`）/ 响应体**不**包含子串（`textNot`）；两端探针都有 `GET /mods`，跨加载器组合时靠它断言「每端到底加载了什么」。
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
     -ClientGameDir '<TARGET_DIR>\client-fabric'
```

**跨族组合**（服务端 fabric + 客户端 forge，或反过来）要在 `client` 块里显式写 `"loader": "forge"`：

```jsonc
"client": { "mode": "standalone", "loader": "forge", "version": "26.3-Forge-test", "root": "<HMCL_HOME>" }
```

不给就默认跟服务端 `loader` 一样。它决定三件事：`gradle` 形态跑哪个 `lab\<loader>` 工程、安装提示里让你用哪个 `-Loader`、
以及运行期「客户端探针报的加载器 ≠ 实例声明的加载器」时给的警告。**加载器不进协议层**，所以跨族能连上；
但被测 mod 必须有对应加载器的版本，装错的 jar 被静默跳过 —— 这只有两侧 `/mods` 断言能发现。

几个已经踩过的点：

- **客户端探针端口固定 39002** ⇒ 同一时刻只能有一个客户端在跑（`devrun` 开跑前会清掉占端口的旧进程）。
- `standalone` 起客户端前会校验 `<客户端目录>\config\mcdev-probe.manifest.json`：没有它直接报错并让你先跑 `install-probe.ps1`
  —— 「客户端起来了但探针没装」会表现成「等探针超时」，比直接报错贵得多。
- 首次启动客户端会先弹一次性界面（无障碍向导、图形警告……），`devrun` 会在连接前按界面自己的语义关掉它，
  **不写你的 `options.txt`**、不按坐标盲点、不认识的界面一律不碰。
- `side` 决定哪一端在场：`server`（只服）/ `client`（只客，没有服务端可起）/ `both`。场景里出现对端不存在的用例时，
  `devrun` 在**加载阶段**就报错，不会跑一半才发现。

### 单人世界：客户端自己开世界

「只在客户端生效的 mod」和「单机里的行为」在专用服上验不了 —— 单人世界由客户端自己起内置服务端。
客户端探针提供两个入口（通用能力，不针对任何 mod）：

| 端点 | 作用 |
|---|---|
| `POST /world/singleplayer?name=<id>&mode=normal\|flat&gamemode=survival\|creative&allowCommands=…&seed=…&reuse=true` | 新建或打开单人世界；返回 `levelId` / `worldDir` / `renamed` / `openedExisting` |
| `POST /world/leave` | 存档并回标题界面 |

- `mode=flat` 用 `FLAT_ALL_DIMENSIONS` 预设：平地、生成快，适合测试；`normal` 是普通地形。
- `reuse=true` 时同名世界存在就**打开**它（同一份数据反复用，不在 `saves` 里越堆越多）；否则新建，
  撞名自动加 `-2` / `-3`，返回 `renamed: true`。
- 创建是异步的：用客户端 `/status` 的 `inWorld` 轮询等它就绪（`scenarios/singleplayer.json` 就是范例）。
- 单人世界里的内置服务端也会加载 `environment: "*"` 的 mod ⇒ 它证明的是「装在同一份客户端里时生效」，
  **不等于**「专用服没装这个 mod 时的表现」。后者要靠两台实例（只看得到各自 `/mods`）。

```powershell
# 纯客户端实例（没有服务端可起，-Dir 可省）
pwsh -NoProfile -File scripts\register-instance.ps1 -Name sp -Side client -Loader fabric `
     -Client standalone -ClientRoot '<HMCL_HOME>' -ClientVersion '26.3-Fabric' `
     -ClientGameDir 'T:\你的客户端目录'
pwsh -NoProfile -File scripts\devrun.ps1 -Instance sp -Scenario scenarios\singleplayer.json
```

### 跑一组用例：装什么 → 跑什么 → 卸干净

`devrun -Mods` 是「跑一次顺手拷进去」，它不记账也不卸载。换一个 case 接着跑时，上一轮装在
`mods\` 里的 jar 还在，这一轮的断言就不知道在验谁。两个脚本补上这一半：

- **`scripts/install-mod.ps1`**：把被测 jar 装进目标目录，并把每个文件的 sha256 记进
  `<目录>\config\mcdev-mods.manifest.json`；`-Remove` 只删登记过且 sha256 对得上的文件
  （被手动换过就停下，`-Force` 才删）；`-List` 看现状。它还会读 Fabric 的 `environment`
  字段，往错了的一侧装时给警告（例如把 `environment: "client"` 的 jar 装进服务端）。
- **`scripts/devrun-matrix.ps1 -Cases <用例文件>`**：按用例串行跑——装服务端/客户端 mod →
  起 `devrun` 跑该场景 → 精确卸掉 → 读 `summary.md` 的 `PASS=n FAIL=m` 行判绿/红。

用例文件长这样（`instance` 是你自己登记过的实例名）：

```json
{
  "cases": [
    {
      "name": "fixture-both",
      "instance": "fam-fabric",
      "scenario": "scenarios/fixture-both.json",
      "serverMods": ["lab/fixture/build/libs/mcdev_fixture-1.0.0.jar"],
      "clientMods": ["lab/fixture/build/libs/mcdev_fixture-1.0.0.jar"],
      "note": "两端都装：握手双向都通"
    },
    {
      "name": "expect-missing-half",
      "instance": "fam-fabric",
      "scenario": "scenarios/fixture-client-missing.json",
      "serverMods": ["lab/fixture/build/libs/mcdev_fixture-1.0.0.jar"],
      "expectFail": true
    }
  ]
}
```

字段：`name` / `instance` / `scenario` 必填；`serverMods` / `clientMods` 是相对仓库根的 jar；
`expectFail: true` 表示「这条本来就该红」，红了才算符合预期（用来正面表达「缺少一半时应该
报错」这类断言）；`note` 只进汇总表。每个用例的取证落在 `<EvidenceDir>\<用例名>\`，
根目录另有一份 `matrix-summary.md`。退出码 0 表示所有用例都符合预期。

实测（2026-10-20）：三条 fixture 用例 **3/3 符合预期**（`tmp\matrix-fixtures`）；
故意写反的期望报「意外变绿」，jar 不存在的用例记「跑挂」并继续下一条（`tmp\matrix-negative`）。
每个用例跑完后两端 `mods\` 都回到基线，没有留下清单文件。
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
| `scripts/` | `devrun` 编排、`devrun-matrix` 用例矩阵、实例登记、部署向导、探针装卸（`install-probe` / `remove-probe`）、被测 mod 装卸（`install-mod`）、打包、DSH 插件安装 |
| `scripts/lib/` | `Env.ps1`（java/代理解析）、`Launch.ps1`（按版本清单拼客户端命令行） |
| `scenarios/` | 场景 JSON（`smoke` / `server-only` / `actions` / `capabilities` / `singleplayer` / `fixture-*`（三端组合的样本场景）；`capabilities` 是套件自身能力的自检，含跨维度读写、客户端聊天/actionbar 断言、日志子串断言） |
| `tools/dsh-plugin/` | DSH 插件（`mcdev_devrun` / `mcdev_scenarios`） |
| `docs/` | 可行性评估、逐阶段移植记录、部署拓扑、状态总览 |
| `docs/examples/` | **参考资料**，不是能力清单：拿一个具体 mod 演示「功能 → 可断言步骤」怎么写（见下节） |
| `lab/fixture/` | **测试样本 mod（Fabric，6.8 KB）**：唯一用途是证明「两端都必须有的 mod」能被断言 —— 服务端等握手、客户端发握手，缺一侧就写日志。不是示例 mod，也不是能力清单 |
| `lab/fixture-client/` | **测试样本 mod（Fabric，3.0 KB，`environment: "client"`）**：证明「只有客户端一半」的 mod 在单人世界/连服时照样生效，而服务端 `/mods` 里根本没有它 |
| `lab/fixture-server/` | **测试样本 mod（Fabric，3.1 KB，`environment: "server"`）**：证明「只有服务端一半」的 mod 在客户端根本没有它时照样收到 JOIN、照样 tick |
| `lab/fixture-forge/` | **同一个样本的 Forge 版（6.9 KB，modId 同样是 `mcdev_fixture`）**：语义与 `lab/fixture` 一致 —— 客户端发握手、服务端收并回 ack、缺一侧就写日志。目前只做到「构建 + 服务端加载实证」（见 `docs/architecture.md` §9.8） |
| `lab/fixture-neoforge/` | **同一个样本的 NeoForge 版（6.8 KB，modId 同样是 `mcdev_fixture`）**：同上 |

先读 [docs/STATE.md](docs/STATE.md) 了解当前进度；[docs/architecture.md](docs/architecture.md) 讲整体设计；[docs/deployment-topology.md](docs/deployment-topology.md) 讲实例与端口分配。

## 参考资料（不是套件能力）

[docs/examples/pairedportals/](docs/examples/pairedportals/) 是一份**参考材料**：它拿一个具体 mod（告示牌配对的自定义传送门）
演示「把功能翻译成可自动断言的步骤」时**可以怎么想** —— 怎么分组、断言落在哪、时序怎么等、哪些必须人眼。
用例每步都有 `note` 说明在验什么。

它**不是**套件的一部分，也**不会**被 `devrun` 默认执行：套件只提供通用能力，不规定你测什么。
给别的 mod 写用例前，先跟使用者确认「有哪些功能要测、成功后可观测的变化是什么、哪些只能人眼看」，再写步骤。

判定**只来自探针/日志/API**（方块状态、命令回执、mod 日志、客户端坐标等）。这套工具做的是首轮判断，
不替代人上手测：音效、动画、观感这类东西没有数据通道，交给人。

## 配合 DSH 的 computer use（可选）

这套件是给 DSH 用的第一轮自动测试：它把「能通过探针/日志/API 判定的部分」跑完，剩下靠人眼的那层交给人。
**computer use 是 DSH 的官方可选插件**（`@deepseek-ai/dsh-computer-use` 加它的 cua-driver MCP provider），
不是本套件的能力 —— 本套件**不实现截图**，也不假装自己能模拟真人。

- **部署时**：`setup-wizard.ps1` 会查 `<DSH_HOME>\profiles\*\cordis.patch.yml` 里有没有启用它，把结论写进实例 json 的
  `computerUse`（`enabled` / `disabled` / `absent` / `unknown`），并打印启用指引（包名两行）；
  `register-instance.ps1 -ComputerUse auto|on|off` 可以显式指定。查不到就用 `-DshHome` 指路，或设 `DSH_HOME`。
- **第一轮跑完之后**：`devrun.ps1` 会打印「下一步」，并把同样的内容写进 `results.json` 的 `nextStep`（`ask=true` 表示该问用户）——
  是直接手测，还是再用 computer use 跑一轮（真实鼠标、真实窗口、真截图）。
- **它擅长什么**：游戏进程之外的东西（启动器、崩溃框、系统弹窗、窗口本身），以及人眼能看的那层（界面观感、紫屏过渡、tooltip）。
- **它的代价**：比探针慢一个数量级；MC 的 3D 窗口基本没有 UI 自动化树，只能靠像素和视觉模型；窗口一移动，坐标全失效。
  所以它**不进判定链**，只在两种时候值得动用：① 首轮绿了，想再走一轮真人路径、顺手留几张图给人看；
  ② **首轮红了** —— `devrun -KeepRunning` 把服务端和客户端留在原地，再用 computer use 进去截图、看可疑点、读现场状态，
  比你靠日志猜要快。

## 许可

原创代码与文档以 **Apache License 2.0** 发布（见 [LICENSE](LICENSE) / [NOTICE](NOTICE)）；派生与骨架部分保留各自许可证，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

Minecraft 是 Mojang Synergies AB 的商标；本仓库不重新分发 Minecraft 客户端、服务端或任何游戏资源。
