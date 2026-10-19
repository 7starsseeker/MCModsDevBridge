# `lab/fixtures/` —— 套件自己的自测样本（不是模板、不是示例 mod）

这里放的是**为了让套件的断言能力本身可被验证**而写的最小 mod，一共五个工程。它们不属于套件能力，也不在发布产物里（`scripts/package.ps1` 只打三份探针 jar，不打 fixture jar）。

## 为什么要在仓库里

「某个 mod 只装在服务端 / 只装在客户端 / 两端都得装」这三种情况，套件必须能把它们变成 **PASS/FAIL**，而不是靠人相信它行。要证这件事，就必须有一个行为完全可控、能自己写日志的 mod —— 而且要覆盖三家加载器。所以有这些样本：

| 目录 | 语义 | `modId` | `environment` | 产物 |
|---|---|---|---|---|
| `fixture/` | **两端都必须**：客户端发握手，服务端收到回 ack；缺一侧就写「missing」日志 | `mcdev_fixture` | `*` | 6.8 KB |
| `fixture-client/` | **只有客户端**：没有 `src/main/java`，只有 client 入口 | `mcdev_fixture_client` | `client` | 3.0 KB |
| `fixture-server/` | **只有服务端**：只有 `src/main/java`，没有 client 入口 | `mcdev_fixture_server` | `server` | 3.1 KB |
| `fixture-forge/` | 同一份「两端都必须」样本的 Forge 版（单 source set + `dist == CLIENT` 守卫） | `mcdev_fixture` | `mods.toml` 的 side=BOTH | 7.0 KB |
| `fixture-neoforge/` | 同 Forge | `mcdev_fixture` | 同上 | 6.8 KB |

`fixture` / `fixture-forge` / `fixture-neoforge` **故意共用 `modId = mcdev_fixture` 和同一串日志文本**（`[mcdev_fixture] client sent hello` 等）——这样同一份场景 JSON 与同一组断言可以跨加载器复用，换的只是「哪个 jar 装进哪个实例」。`fixture-client` 与 `fixture-server` 的 `modId` 不同，因为它们本来就是另外两类样本（`/mods` 里必须能区分谁在场）。

## 构建

每个工程都是独立的 Gradle 工程（骨架来自对应的 `lab/<loader>/`）：

```powershell
cd lab\fixtures\fixture           ; .\gradlew.bat build     # Fabric
cd lab\fixtures\fixture-forge     ; .\gradlew.bat build     # Forge
cd lab\fixtures\fixture-neoforge  ; .\gradlew.bat build     # NeoForge
```

产物在各自的 `build\libs\`。`lab/fixture*` 里**没有**从 `../shared` 引入任何东西 —— 它们是样本，不是探针的一部分。

## 怎么用

配套场景在 [`scenarios/fixtures/`](../../scenarios/fixtures/)，用 `scripts\devrun-matrix.ps1` 一次跑一组（它会负责装 jar、跑、卸干净）：

```powershell
pwsh -File scripts\devrun-matrix.ps1 -Cases tmp\my-cases.json
```

用例文件的形状见根 `README.md` 的「跑一组用例」一节，或 `tools\dsh-plugin\mcdev-devrun\skills\mcdev-fixture-design\SKILL.md`。

## 别拿它们当模板

要写自己的 mod，用 `lab/<loader>/` 里的骨架或你自己的脚手架。这些样本为了「一句话说清一个断言」而刻意写得很薄，缺了真实 mod 会有的注册表、数据生成、网络层 —— 照抄只会学到一个空壳。
