# mcdev-devrun — DSH 插件（薄包装）

把本仓的端到端编排脚本 `scripts/devrun.ps1` 包成 DSH 工具。注册两个工具：

| 工具 | 作用 |
|---|---|
| `mcdev_devrun` | 起测试服 → 起客户端（`gradlew runClient`）→ 连服 + `op` → 执行场景 JSON → 取证 → 收尾。`family=fabric|forge|neoforge|all`，`all` 串行跑三家并汇总（客户端探针端口 39002 共用，**不能并行**） |
| `mcdev_scenarios` | 列出 `scenarios/*.json` 的名称、说明、步骤数 |

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
node tools\dsh-plugin\selftest.mjs
```

用桩 `ctx` 调 `apply()`：断言注册了两个工具、名字/schema/`output.render` 形状、`family` 枚举，并真跑一次 `mcdev_scenarios` 确认能列出场景文件。它验证「注册阶段不会炸」，**不**验证 DSH 运行时集成（那要重启）。

## 依赖

- `pwsh`（PowerShell 7）在 PATH；
- 工作区里有 `scripts/devrun.ps1`；
- 跑 `family=all` 时三家测试服目录要存在（`<MC_ROOT>\Server{,1,2}`），且 `mods/` 里有对应探针 jar。
