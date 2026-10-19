# mcdev-devrun — DSH 插件（薄包装）

把本仓的端到端编排脚本 `scripts/devrun.ps1` 包成 DSH 工具。注册两个工具：

| 工具 | 作用 |
|---|---|
| `mcdev_devrun` | 起测试服 → 起客户端（`gradlew runClient`）→ 连服 + `op` → 执行场景 JSON → 取证 → 收尾。`family=fabric|forge|neoforge|all`，`all` 串行跑三家并汇总（客户端探针端口 39002 共用，**不能并行**） |
| `mcdev_scenarios` | 列出 `scenarios/*.json` 的名称、说明、步骤数 |

另外把「只有真机才知道」的开发知识注册成 **11 个 skill**（见 §知识层）：命中触发词时按需加载，不占常驻 context。

## 它不做什么

- **不复制编排逻辑**：端口、句柄继承、玩家名每次变、世界残留这些坑都在 `scripts/devrun.ps1` 里解决过一次了。插件只做参数校验 + 把输出裁成「结论行 + 取证路径 + summary.md 前几行 + 输出尾巴」。
- 不自动装自己、不自动重启 DSH。
- **不实现截图**：真截图与真实鼠标由 DSH 的官方可选插件 computer use 提供。mcdev_devrun 的输出尾巴里会带上 devrun 打印的「下一步」（直接手测 / 再用 computer use 跑一轮；有失败项时 -KeepRunning 留现场再进去看），agent 照它问用户就行。

## 工作区定位

按顺序找 `scripts/devrun.ps1` 所在目录：安装时写下的 `mcdev-root.txt`（stamp）→ `MCDEV_ROOT` 环境变量 → 从工具调用的 `cwd` 往上最多 8 层。三者都不中就直接报错（不猜路径）。

## 安装 / 回滚

```powershell
# 只看会做什么
pwsh -NoProfile -File scripts\install-dsh-plugin.ps1 -Mode dryrun

# 装（复制插件 + 往 $DSH_HOME/cordis.patch.yml 追加 mount 段；改前自动备份）
pwsh -NoProfile -File scripts\install-dsh-plugin.ps1

# 卸载
pwsh -NoProfile -File scripts\install-dsh-plugin.ps1 -Mode uninstall
```

**必须重启 DSH 才生效**（配置只在加载时读取）。回滚也可以直接把 `$DSH_HOME/cordis.patch.yml.bak-<时间戳>` 覆盖回去。

## 自测（不用装、不用重启）

```powershell
node tools\dsh-plugin\selftest.mjs                                # 两个工具的注册形状
node tools\dsh-plugin\mcdev-devrun\selftest-skills.mjs            # 知识层：正文可读 / 双向一致 / references 可达
```

用桩 `ctx` 调 `apply()`：断言注册了两个工具、名字/schema/`output.render` 形状、`family` 枚举，并真跑一次 `mcdev_scenarios` 确认能列出场景文件。它验证「注册阶段不会炸」，**不**验证 DSH 运行时集成（那要重启）。

## 知识层（skills）

`skills/<名字>/SKILL.md` + `references/*.md`，由 `skills-provider.mjs` 注册成一个 skill provider
（provider 名 `mcdev`，`source: bundled`，rank 与 dsh-skill 的 `BUNDLED_SKILL_RANK` 对齐）。
**不在 `dsh.mjs` 的顶层 `inject` 里声明 `skills`**，而是 `ctx.inject(['skills'], ...)`：
skills 服务缺席时只是没有知识层，工具照常注册（`selftest.mjs` 的桩 ctx 正好只提供 `tools`，当场证明了这条）。

| skill | 什么时候会命中 |
|---|---|
| `mcdev-probe-protocol` | 要发探针请求 / 写断言前：信封与错误码、token、`/status` 能力发现、只认 query 的端点、501 = 打错一侧 |
| `mcdev-devrun` | 要写或跑场景：实例登记、参数、矩阵、取证目录、失败定因 |
| `mc26-multiloader-traps` | 写 26.3 mod 或排查「代码看着对但不生效」：Screen 搬家、SDL、静默失败、断线 gamerule |
| `mc-verification-design` | 设计测试场景：四类断言边界、retry vs waitMs、「只对了一半」怎么表达、什么只能人眼看 |
| `mc26-mod-skeleton` | 从零搭 26.3 工程 / 加一个 fixture：三族构建版本事实表、split source sets、三份元数据、客户端类放错源集 |
| `mcdev-fixture-design` | 证明「某一侧真的生效」或「只装了一半」：五 fixture 装配矩阵、四层判据、已知边界 |
| `mc-world-assertions` | 断方块/实体/位置：「命令成功但没生效」的三种静默失败、回读权威性四层、未加载区块假阴性、位置三分字段 |
| `mc-content-data-verification` | 验配方/合成/掉落：/craft 不是真合成、材料失败不退还、/loot 只解默认状态、/items 是附近掉落物 |
| `mc-multiplayer-verification` | 验多人：单客户端边界、39002 串行、/connect 静默不重连、/player/state 才是权威、断言落在另一个人身上 |
| `mc-crash-diagnosis` | 「起不来」三分类：/status → /mods → /log/tail → 文件；log/tail 干净≠没异常；端口退默认的假崩溃 |
| `mcdev-instance-setup` | 部署 / 换机器 / 实例认不到 / 代理镜像离线：L0-L2 拓扑、三条取运行时路线、`instances\<name>.json` 全字段 |

附 5 份 `references\*.md`（端点全表、GUI 自动化、场景 DSL、场景配方、排障）。

加一个 skill = 建 `skills\<名字>\SKILL.md` + 在 `skills-provider.mjs` 的 `SKILLS` 表里加一行
（`description` 是模型的**路由信号**，写「什么时候该用我」）。`selftest-skills.mjs` 会双向核对：
登记了没目录、有目录没登记、`references/*.md` 引用断了，都会 FAIL。

`skills-provider.mjs` **刻意不 import `@deepseek-ai/dsh-skill`**：插件目录没有 package.json，
import 裸包名要多一条解析路径；rank 只需与它的常量（600）对齐，写死数字更可靠。

## 依赖

- `pwsh`（PowerShell 7）在 PATH；
- 工作区里有 `scripts/devrun.ps1`；
- 跑 `family=all` 时三家测试服目录要存在（`<MC_ROOT>\Server{,1,2}`），且 `mods/` 里有对应探针 jar。
