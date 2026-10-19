# 参考用例：用探针与日志证一个具体 mod 的功能（PairedPortals）

> **这是参考材料，不是套件的流程。**
> 套件只提供**通用能力**（起服/起客户端/驱动动作/读状态/断言/日志），不规定你该测什么。
> 这份材料的作用是：以后给**别的 mod** 写用例时，这里的分组方式、断言落点、时序处理可以当思路参考。
> 它不是「新 mod 就按这 43 步跑一遍」的模板 —— 每个 mod 该测什么必须先跟使用者确认（见最后一节）。

用例对象：`PairedPortals`（告示牌文字配对的自定义传送门，纯服务端逻辑）。这是个**已经手测过、投入正式使用**的 mod，
所以这份用例的用途是「验证这套工具能不能把它的功能自动证出来」，不是「替它找 bug」。

- 用例：`case.json`（43 步，每步 `note` 写清这条在验什么）
- 实测：Fabric 26.3，`PASS=43 FAIL=0 total=43`

## 判定从哪来

只有三处，全是机器可读的：

| 来源 | 端点 | 这份用例里用来断言什么 |
|---|---|---|
| 世界状态 | `GET /world/block?dim=&x=&y=&z=` | 门洞真的变成了 `nether_portal` / 真的熄了 / 框真的没了 |
| 控制台回执与 mod 日志 | `GET /log/tail?n=` | 命令回执（`System chat: ...`）与 `[pairedportals] ...` 事件行 |
| 客户端状态 | `GET /status`（客户端） | `playerPosX/Y/Z`：穿门前后坐标是「真的过去了」的硬证据 |

外加 `POST /command`（搭方块、执行 mod 自己的命令）、`POST /action/*`（传送玩家、召唤实体）作为**驱动手段**。
不需要人眼盯屏幕：这套逻辑全是服务端可观测的。

## 这份用例的分组方式（可复用的是「分组」这件事）

| 功能 | 步骤 | 判据 |
|---|---|---|
| 搭框 | `frame-a` / `frame-b` / `hole-a` / `hole-b` | `/world/block` 逐格读回黑曜石与门洞空气 |
| 告示牌写名字 | `sign-a` / `sign-b-same-text` | `/block/sign` 读回四行文字（这也是配对用的门名） |
| 点火激活 | `ignite-a` / `portal-a-left/right` | 门洞两列读回 `minecraft:nether_portal` |
| 命令可用 | `list-*` / `info-*` | 命令回执进日志，`expect.text` 断整行文案 |
| 配对 | `log-2-bound` / `log-b-bound` | `2 portal(s): 1 bound pair(s) (2 door(s))`、`state: BOUND` |
| 穿门 | `player-into-portal` / `player-travelled` / `log-travel` | 客户端 `playerPosX` 由 `53.5` 变 `58.5`；日志 `travel:` |
| 拆一端全灭 | `break-frame-a` / `twin-hole-cleared` / `log-3-twin-gone` / `log-4-zero` | 另一端门洞也读到 air（**用 `retry` 等下一 tick**）；日志 `went down with its twin`；计数回 0 |

## 断言选择的四个坑（都是实测踩出来的，与 mod 无关）

1. **`/command` 的返回码不能当成功信号**：`execute in ... run setblock` 明明改了方块也回 `0`，区块没加载时也回 `0`。
   断言落在**副作用**上（再读一次方块状态），不拿 `result` 当判据。
2. **mod 的时序行为要 `retry`**：配对门在破坏后的下一 tick 才熄灭，同一瞬间断言必然拿到旧值。
3. **坐标用 `playerPosX` 而不是 `playerPos`**：后者是 `Vec3.toString()` 的字符串（`(53.5, 100.0, 8.0)`），
   按子串断言要猜格式。
4. **会遗留状态的东西要清场**：上一轮留下的实体会自己穿门，日志里多出 `travel` 行 —— 用例开头先 `kill` 一遍。

## 它证明不了什么（交给手测）

音效、界面动画、F3 上的读数、紫黑色缺材质方块的观感 —— 这些没有数据通道，自动断言给不出结论。
这套工具做的是**首轮判断**：功能有没有真的发生、状态对不对、日志里有没有异常；剩下的交给人。

## 换一个 mod 时怎么开始

不要从这里抄步骤。先跟使用者确认三件事，再写用例：

1. 这个 mod **有哪些功能**要测（以及哪些明确不测）；
2. 每个功能**成功后世界/客户端会出现什么可观测的变化**（方块、实体、日志行、命令回执、坐标、界面文字）；
3. 哪些结论**只能人眼看**，那部分不进用例、直接交给人。

确认完再写步骤：一步一个可断言的副作用，`note` 里写清「这条在验什么」。
这份演示不进套件的能力清单，也不随套件默认执行 —— 套件不带任何针对具体 mod 的用例。

## 怎么跑这份参考用例

```powershell
pwsh -File scripts\devrun.ps1 `
  -Instance fabric `
  -Scenario docs\examples\pairedportals\case.json `
  -Mods '<你的 mod jar 路径>' `
  -EvidenceDir tmp\devrun-pp-demo
```

看 `tmp\devrun-pp-demo\summary.md`（每步一行，含「说明」列与失败原因）与 `results.json`（机器可读）。
