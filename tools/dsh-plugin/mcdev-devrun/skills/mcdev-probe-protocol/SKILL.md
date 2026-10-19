# MC 探针 HTTP 契约（mcdev-probe-protocol）

> 探针是本套件的**执行层**：一个跑在 Minecraft 服务端的 mod，一个跑在客户端的 mod，各自起一个
> localhost HTTP 服务。所有自动化（场景断言、动作、取证）都打到这两个端口上。
> 端点全表见 `references/endpoints.md`；GUI 自动化（`/ui/*` 的坐标语义、`onClick` 必补、截图命名与
> 人眼边界）见 `references/ui-and-screenshots.md`；这里讲**共同契约**和最容易踩的三条。

## 1. 两个探针，职责不同

| | 服务端探针 | 客户端探针 |
|---|---|---|
| 跑在哪 | 专用服 / 集成服 | 客户端（`runClient`） |
| 默认端口 | 39001 | 39002 |
| 天然权威的东西 | 世界方块、实体列表、配方/掉落表、命令执行、玩家状态 | 键鼠输入、GUI 树、截图、聊天/动作栏显示、HUD |
| 打错一侧的表现 | `/screenshot`、`/ui/*` 返回 **501** `client-only endpoint` | `/world/break` 等世界里没有的端点不存在（404） |

- **`data.side` 永远是先手**：`GET /status` 返回 `data.side = "server" | "client"`。写脚本前先读它，
  别靠端口推断——端口可配（`-Dmcdev.probe.port=<n>`，见 `ProbeConfig`）。
- 服务端那几个 `client-only` 端点不是不存在，是**显式 501**（`ProbeServer.handleNotClient`）：
  这样「打错一侧」是个能读懂的错，而不是 404 让人以为路径写错了。

## 2. 信封与错误码

所有响应都是同一层信封（`ProbeServer.java:1164`）：

```json
{"ok": true,  "data": { ... }}
{"ok": false, "error": {"code": "BAD_REQUEST", "message": "..."}}
```

- **HTTP 状态码与 `ok` 一致**：成功 200，业务错误 400/404/409，超时 504，鉴权 401，打错一侧 501。
- 断言时优先看 `status` + `error.code`，不要只匹配 message 文案——文案会改，code 是契约。
- `error.code` 的实现见 `lab/shared/src/main/java/com/mcdev/probe/action/ActionException.java`：
  动作层的错误都要落到具体 4xx，**不允许一律 500**。

## 3. 鉴权

- 请求头 `X-Mcdev-Token`，默认值 `mcdev`（`ProbeServer.java:63`）。
- 该值配成**空串 = 关闭鉴权**（`:1151`）；token 不匹配回 `401 invalid token`。
- 起服时通过 `-Dmcdev.probe.token=<v>` 改；客户端同理。编排脚本会自己带上，手搓 curl 时别忘了。

## 4. 三条最容易踩的

1. **`/connect` 只认 query，不认 body。** 套件曾经按 body 发 → 被静默忽略 → 连到默认端口 25566 →
   永远进不了世界（`ClientProbeServer.java:168` 的注释就是这条的墓碑）。
   → 形如 `POST /connect?host=127.0.0.1&port=25566`。同理 `/world/*`、`/log/tail`、`/chat/tail` 全是 query。
2. **`POST /command` 的 `ok:true` 只证明「命令被服务端接受」**，不证明命令产生了你要的效果。
   比如 `tp` 成功但玩家没动、`give` 成功但背包没变——判定要回读（`/player/state`、`/player/inventory`），
   不要拿 200 当断言。
3. **动作集合是后端自己声明的，不是固定清单。** `GET /status` 的 `data.actionBackend`
   （`server` / `client`）与 `data.actions`（数组）才是真相。写场景前读一次，别按本文档的例子硬编码。

## 5. 时间推进（服务端）

- `data.frozen` / `data.stepBudget` 来自 `TickControl`：可以**冻结 tick** 再按步推进，
  用来做「等 N tick 后世界应变成 X」这类确定性断言，而不是 sleep 撞运气。
- 冻结状态下真实玩家会感到卡顿——只在测试服、只在需要的窗口期冻结。

## 6. 下一步

- 要发请求 / 写断言：先看 `references/endpoints.md` 的端点全表。
- 要把它编成一条可跑的流水线：转到 `mcdev-devrun` 技能（场景 JSON 语法 + 取证目录）。
- 要判断「这个功能到底能不能自动验」：转到 `mc-verification-design`。
