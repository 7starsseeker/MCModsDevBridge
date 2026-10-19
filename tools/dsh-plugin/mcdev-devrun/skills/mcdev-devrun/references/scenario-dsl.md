# 场景 JSON 全语法（mcdev-devrun）

> 权威说明在 `scripts/devrun.ps1` 的 `.PARAMETER Scenario` 注释里，本文是它能表达的全部能力 + 用法建议。
> 现成例子：`scenarios\smoke.json`（最小可用）、`scenarios\capabilities.json`（14 步跨维度 + 聊天/动作栏）。

## 1. 骨架

```json
{
  "name": "smoke",
  "description": "这一段在验什么（会进 summary.md）",
  "steps": [
    { "id": "server-status", "target": "server", "method": "GET", "path": "/status",
      "expect": { "status": 200, "json": { "data.side": "server" } } }
  ]
}
```

- `target`：`server`（服务端探针）或 `client`（客户端探针）。
- `method` / `path`：HTTP 方法与路径；query 直接写在 `path` 里。
- `body`：POST 的 JSON body（有的端点只认 query，body 会被静默忽略，见 `mcdev-probe-protocol`）。
- `id`：必填，出现在 results.json / summary.md，**排障时按 id 定位**，取能读懂的短名。
- `note`：可选，写「这条在验什么」，只进 results.json 与 summary.md 的说明列，不影响判定。
  给以后读证据的人（和写用例的自己）看，一句话即可。

## 2. 四类断言（`expect`）

```json
"expect": { "status": 200, "json": { "data.applied": true } }
```

| 字段 | 语义 | 什么时候用 |
|---|---|---|
| `status` | HTTP 状态码相等 | 永远先写它 |
| `json` | 按**点号路径**取字段，**字符串化后相等** | 布尔/数字/枚举字段 |
| `contains` | 按点号路径取字段，断言其**字符串形式包含子串** | 列表、拼接文本（如 `data.ids` 里有某个 modid） |
| `text` | 原始响应体包含这些子串（数组 = 全部要包含） | 日志类端点（`/log/tail`、`/chat/tail`） |
| `textNot` | 原始响应体**不**包含这些子串 | 日志层面的「不该出现」（如 `server got hello` 不该出现） |
| `notContains` | 按点号路径取字段，其字符串形式**不能**包含子串 | 字段层面的「不该出现」（如客户端 `/mods` 的 `data.ids` 不含某 mod） |
| `statusIn` | 状态码属于给定数组 | 同一端点 200 / 4xx 都算预期时（比写两个步骤省事） |

- `json` 的比较是「字符串化后相等」，所以 `true` 与 `"true"` 不会互相误判——按你实际拿到的类型写即可。
- 用 `contains` 而不是 `json` 判列表内容：列表顺序不稳定，等值断言会很脆。
- 用 `textNot` 表达「不该发生」，比把整段日志写成等值断言稳得多。
- 每步可写 `"note": "这条在验什么"`：只进 `results.json` 与 `summary.md` 的「说明」列，**不参与判定**。
- 断言失败时先读这一步的**原始响应体**（`results.json` 的 `steps[].body`）；`devrun` 的控制台只给理由摘要。

## 3. 时序：`retry` 优先，`waitMs` 兜底

```json
{ "id": "client-chat", "target": "client", "method": "GET", "path": "/chat/tail?n=40",
  "retry": { "times": 20, "intervalMs": 250 },
  "expect": { "status": 200, "contains": { "data.text": "probe-chat-line" } } }
```

- `retry`：最多发 `times` 次、间隔 `intervalMs`，**任一次断言通过即停**（早退）。
  这比「写死 waitMs 然后赌一次」稳，也不会在快的时候白等。
- `waitMs`：只在该步骤**首次**尝试前生效，用于「上一步的副作用要等一下」。
- 经验值：服务端 tick 类副作用（方块更新、掉落、动作栏显示）用 `times=20, intervalMs=250`（≈5s 上限）；
  纯网络往返不需要 retry。

## 4. 占位符

- `{{player}}`：替换成这次运行实际登录的玩家名（名字每次变，别写死）。常用于
  `/command` 里的 `title {{player}} actionbar {...}`、`tp {{player}} ...`。
- 占位符在**整个字符串**里替换，JSON 转义按普通文本处理。

## 5. 故意失败也是契约

`scenarios\smoke.json` 的最后一步就是故意打一个非法 gamemode：

```json
{ "id": "loud-failure-unknown-gamemode", "target": "server", "method": "POST",
  "path": "/action/gamemode", "body": { "mode": "nonsense" },
  "expect": { "status": 400, "json": { "error.code": "BAD_REQUEST" } } }
```

**错误码是契约的一部分**：只要接口还回 400 + 正确 code，这条就绿；哪天它变成 500 或静默成功，
这条立刻红。比写一段「希望它失败」的注释有用得多。

## 6. 「只对了一半」怎么表达

- 想要的是「这个用例本来就该红」→ 用矩阵用例文件的 `expectFail: true`（不是场景内的字段）。
- 想要的是「A 生效但 B 没生效」→ 拆成两条：一条验 A（应该绿），一条验 B（应该红，且红的原因可读）。
  不要用一条步骤同时表达两件事，红了以后看不出是谁的问题。
- 想要的是「这条今天做不到，记着」→ 别写成永远绿的占位步骤；写在用例文件的 `note` 里。

## 7. 编写顺序建议

1. 先用 `-PreflightOnly` 确认实例/端口/探针 jar 都对（这一步不需要场景）。
2. 第一条永远是两侧 `/status`：它同时证明「探针起来了」「连的是哪一侧」「哪个 actionBackend」。
3. 再逐步加：一次只加一条断言，红了就知道是新加的那条。
4. 最后加故意失败的那条，把错误码钉住。
