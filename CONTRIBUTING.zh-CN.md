# 参与贡献

> [English](CONTRIBUTING.md) | **简体中文**

谢谢来看。本仓库只收**小而带证据**的改动：PR 里写「能跑」却不附命令和输出摘录，会被退回来。

## 这个仓库是什么

给 **Minecraft 26.3** mod 开发配的一套 agent 工具：服务端探针 + 客户端探针、一条把你从「起服」带到「收尾」的编排脚本，以及把同一套编排包成两个工具的 DSH 插件。

它**不是**给玩家用的 mod，也**不随仓库带任何游戏文件** —— 没有加载器、没有客户端、没有服务端、没有存档。真正会跑游戏的东西由每个人在自己机器上准备并登记。

| 在范围内 | 不在范围内 |
|---|---|
| `lab/shared/`、`lab/{fabric,forge,neoforge}/` 的探针 Java | 针对某个具体 mod 的测试用例 ——「该测什么」是使用者的问题，不是本仓库的 |
| `scripts/` 编排（`devrun.ps1`、安装与实例脚本） | 把客户端 / 服务端 / 加载器塞进仓库 |
| `scenarios/` 示例与自检场景 | `instances/`（含本机路径，见「绝不提交」） |
| `tools/dsh-plugin/mcdev-devrun/`（两个工具 + 11 个 skill） | `docs/` 里的历史记录，除非改动就是在修它 |

## 环境

Windows（编排是 PowerShell）、**PowerShell 7**、**Node ≥ 20**、**Java 25**，以及你自己的 MC 26.3 服务端 + 客户端。

插件和脚本都没有构建步骤、没有运行时依赖：`git clone` 之后直接跑下面的自检。

## 先登记实例

所有端到端路径都从登记开始，因为套件不携带游戏：

```powershell
pwsh -File scripts/setup-wizard.ps1        # 问加载器 / 哪一端 / 现成还是新建 / 代理，写进 instances/
pwsh -File scripts/register-instance.ps1   # 或者你已经很清楚自己要描述哪台机器
```

`instances/` 是故意 git-ignored 的。想只解析登记、不起游戏：

```powershell
pwsh -File scripts/devrun.ps1 -Family fabric -PreflightOnly
```

## 开 PR 之前要跑的自检

两条都离线、不需要游戏、一秒级：

```powershell
node tools/dsh-plugin/selftest.mjs                        # 注册逻辑 + 包元数据 + patch 一致性
node tools/dsh-plugin/mcdev-devrun/selftest-skills.mjs    # 每个 skill 有正文、每个引用可达
```

CI（`.github/workflows/selftest.yml`）在 Node 20 与 22 上跑的就是这两条，所以红了是真失败，不是本地环境怪。`publish.yml` 发布前还会再跑第一条，两边不会各说各话。

它们**不覆盖**什么：DSH 运行时集成。插件改动只有在 DSH 重启后才算真的加载成功 —— 你重启了就说明重启了，没重启也请直说。

## 面向游戏行为的改动，只有真跑一次才算证明

```powershell
pwsh -File scripts/devrun.ps1 -Family fabric -Scenario scenarios\smoke.json
```

`-Family` 取 `fabric` / `forge` / `neoforge` / `all`；`-Instance` 指定登记；`-Scenario` 选场景 JSON；`-Mods` 把被测 jar 拷进**测试服**的 `mods/`（客户端那一端用 `scripts/install-mod.ps1 -Target <实例> -Side client -Jar <jar>`）；`-EvidenceDir` 指定取证目录；`-KeepRunning` 留着让人类看。

**只说你的跑法撑得住的那句话。**「`-Family fabric`、mod 在服务端这一侧通过」是关于一个加载器、一侧的结论，别推广成三家或两端。

有些东西没有数据通道 —— 音效、转场动画、F3 读数、连招手感。别用场景 JSON 去断言它们，直接写明「仍要人进游戏看」。

## 结论要有证据

PR 里每一句「能跑」「修好了」都要带命令和**原始**输出摘录，不要转述。数字必须来自真实执行，不能从实现里抄：断言复述了被测常量，只证明「同一个数字写了两遍」，还把实现锁死。

## 增删 skill

skill 放在 `tools/dsh-plugin/mcdev-devrun/skills/<名字>/SKILL.md`（正文需要细节表时再加 `references/`），由 `skills-provider.mjs` 的登记表加载。**登记了但没有目录**、或**有目录但没登记**，`selftest-skills.mjs` 两个方向都会报错；引用了不存在的 `references/xxx.md` 同样报错。

两份 README 都有 `skills-N` 徽章：增删 skill 要**同时**更新两份文件里的这个数字。

## 文档：面向使用者的才双语

README 是一对 —— `README.md` 与 `README.zh-CN.md`，改动在同一次提交里完成。`docs/` 下的记录是中文写的历史，不做回溯翻译。

## 绝不提交

- `instances/`、`docs/local/` —— 本机路径、端口、代理地址；
- 任何 token、key、`secrets.json`；
- `dist/`、`tmp/`、`.cache/`、`lab/*/build/`、`lab/*/.gradle/`、`lab/*/run/` —— 由 `scripts/package.ps1` 重建；
- 装进了整个游戏的取证目录（正常应该只有几十 KB 日志）；
- 从真实会话里抄来、没做过脱敏的任何内容。

## 上游署名

探针 Java 与插件源自 [`yan-mc/minecraft-mod-dev`](https://github.com/yan-mc/minecraft-mod-dev)（MIT，`Copyright (c) 2026 yan-mc`）。该许可要求保留的声明在 [`NOTICE`](NOTICE) 与 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) 里；删掉或裁剪它们的 PR 会被退回。

## 报问题而不是改代码

问题走 [issues](https://github.com/7starsseeker/MCModsDevBridge/issues)。有用的报告带：加载器、MC 与 Java 版本、完整命令行、取证目录 —— 或者失败的断言和打脸它的响应体。

## 许可

Apache-2.0。提交 PR 即表示你同意该改动以这个许可发布。
