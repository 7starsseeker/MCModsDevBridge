# Third-party notices

本仓库**原创代码与文档**以 **Apache License 2.0** 发布，见 [LICENSE](LICENSE)。
本仓库**同时包含派生自第三方项目的代码与脚手架**，那些部分保留各自的许可证，明细如下。
分发时必须随附 `LICENSE`、`NOTICE`、本文件三者。

---

## 1. minecraft-mod-dev（MIT）— 派生来源

| 项 | 值 |
|---|---|
| 上游 | https://github.com/yan-mc/minecraft-mod-dev |
| 版权 | Copyright (c) 2026 yan-mc |
| 原许可 | MIT |
| 本仓库中的派生部分 | `lab/shared/` 与 `lab/{fabric,forge,neoforge}/` 的探针 Java（源自上游 `lab/mcdev-probe/`）；`tools/dsh-plugin/`（源自上游 `plugin/minecraft-mod-dev/`） |

MIT 的授权条款包含 **sublicense**，因此本仓库可以整体改用 Apache-2.0。
MIT 的唯一义务是保留版权声明与许可全文 —— 已在 [NOTICE](NOTICE) 中完整转载，分发时须一并携带。

> 注意：本仓库中「由官方 MDK 生成的三份构建骨架」**不是**从 minecraft-mod-dev 继承的，它们分别来自各自的上游 MDK，见第 2–4 节。

---

## 2. Minecraft Forge MDK（LGPL 2.1）

Forge 侧骨架生成自官方 MDK。MDK 自带的 `LICENSE.txt` 原文开头：

> Unless noted below, Minecraft Forge, Forge Mod Loader, and all parts herein are
> licensed under the terms of the LGPL 2.1 ...

该 `LICENSE.txt` 随生成的项目目录一同保留，不并入本仓库的 Apache-2.0。

---

## 3. NeoForge MDK（MIT）

NeoForge 侧骨架生成自 `https://github.com/NeoForgeMDKs/MDK-26.3-ModDevGradle`
（`neoforged/MDK` 主仓在 26.3 上只有一个 README，实际模板在镜像组织下）。

已一手核实：该 MDK 根目录的 `TEMPLATE_LICENSE.txt`（1,183 B）为 **MIT License，
Copyright (c) 2023 NeoForged project**，文件首段原文：

> This license applies to the template files as supplied by github.com/NeoForged/MDK

该文件随生成的项目目录一同保留，不并入本仓库的 Apache-2.0。

---

## 4. Fabric example mod（CC0 1.0）

Fabric 侧骨架生成自 `FabricMC/fabric-example-mod`。该项目的 `LICENSE` 为
**CC0 1.0 Universal**（公共领域贡献，无署名义务）。已核实：文件 7,056 B，
首行 `Creative Commons Legal Code` / `CC0 1.0 Universal`。

---

## 5. Gradle wrapper（Apache-2.0）

各项目目录下的 `gradle/wrapper/gradle-wrapper.jar` 来自 Gradle 项目，随 Gradle 自身许可证分发。

---

## 6. Minecraft / Mojang

Minecraft 是 Mojang Synergies AB 的商标。本仓库**不重新分发** Minecraft 客户端、服务端或任何资源文件
（贴图、音效、语言文件、模型）。测试用的方块 / 物品模型在运行时引用原版贴图路径，而非打包副本。

一切对 Minecraft 的使用仍受 **Minecraft EULA** 约束 —— 该约束独立于本仓库的 Apache-2.0，
换证不改变它。

---

## 7. DeepSeek Harness（DSH）

插件面向 DSH，使用其公开插件 API。DSH 各包保留自身许可证。

## 8. Agent Teams

插件挂载 DSH 官方实验性 Agent Teams bundle。该 bundle 为实验性，不提供稳定性保证。

## 9. Mixin / MixinGradle

探针使用 Mixin 与 MixinGradle，各自保留上游许可证。

## 10. Carpet（仅研究引用，不随仓库分发）

`gnembon/fabric-carpet` 的假玩家实现仅在调研文档中被引用与讨论，**其 jar 与源码均未并入本仓库**。
若后续直接复制其代码，必须单独记录其许可证（Carpet 为 MIT）。

---

## 分发检查清单

- [ ] `LICENSE`（Apache-2.0 全文，11,358 B，未改动）
- [ ] `NOTICE`（含 yan-mc 的 MIT 全文转载）
- [ ] `THIRD_PARTY_NOTICES.md`（本文件）
- [ ] 各生成项目内的 MDK `LICENSE.txt` 未被删除
- [x] 第 3 节（NeoForge MDK 许可证）已回填：MIT / Copyright (c) 2023 NeoForged project
- [x] `NOTICE` 与 `LICENSE` 中的版权行已确认为真实的版权持有人：`7starsseeker`（用户 m01574 确认）
