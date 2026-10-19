# GUI 自动化与截图（`/ui/*` + `/screenshot`）

> 来源：`lab/shared/src/client/java/com/mcdev/probe/ClientProbeServer.java`（路由 106-110、`handleUiSnapshot` 283、
> `handleUiClick` 327、`handleUiHover` 385、`handleUiHoverSlot` 418、`handleUiClose` 459）与
> `docs/p3-client-record.md` 的真机实测记录。**记录里已作废的结论不要引**（见文末）。

## 1. 26.3 拿「当前界面」的唯一通路

```java
Minecraft mc = Minecraft.getInstance();
Screen s = mc.gui.screen();      // 无界面 → null
Gui overlay = mc.gui.overlay();  // toast / 调试层，另一套对象
```

`Minecraft.screen` 字段和它的 getter **在 26.3 已不存在**，只剩 `setScreenAndShow(Screen)`；
`Screen` 本身搬到了 `net.minecraft.client.gui.Gui` 这一侧（`Screen.mouseClicked(double,double,int)` →
`GuiEventListener.mouseClicked(MouseButtonEvent, boolean)`）。

`/ui/*` 的五个处理器全部通过 `onClient(...)` 在**客户端主线程**执行，所以「200 = 这次调用已经在主线程跑完」，
不是「已排队」。

## 2. `/ui/snapshot`：控件树

```
GET /ui/snapshot
→ {"screen":"TitleScreen","title":"...","pauseScreen":false,"overlay":null,
   "widgets":[{"type":"Button","x":..,"y":..,"w":..,"h":..,"label":"Singleplayer","active":true,"hovered":false}, ...]}
```

- `screen` / `title` / `pauseScreen` / `overlay` + `widgets[]`；无界面时 `screen:null`、`widgets:[]`。
- 每个控件八字段：`type`（简类名）、`x/y/w/h`、`label`（`getMessage()`）、`active`、`hovered`。
  只有 `AbstractWidget` 才有几何与 label；其他 `GuiEventListener` 只出 `type`。
- 实测规模：`AccessibilityOnboardingScreen` 6 个、`TitleScreen` 9 个、`OptionsScreen` 14 个。
- **没有 id / index / selector**——坐标就是唯一句柄。要做「点第 3 个按钮」，自己按 `label` 或顺序在场景里算坐标。

## 3. `/ui/click`：坐标点，且分两条激活路径

```
POST /ui/click?x=<double>&y=<double>[&button=0]
→ {"screen":"TitleScreen","hit":"Button","hitLabel":"Options...","dispatched":true,
   "activatedVia":"widget-onClick","clicked":true}
```

内部顺序（这是**真机踩出来的关键差异**）：

1. `s.mouseMoved(x, y)`；
2. 遍历 `s.children()`，取第一个 `isMouseOver(x, y)` 的控件 → `hit`（附带 `hitLabel`）；
3. `dispatched = s.mouseClicked(ev, false)`；
4. 若 `hit` 是 `AbstractWidget`：`aw.mouseReleased(ev)` + **`aw.onClick(ev, false)`**，`activatedVia="widget-onClick"`；
   否则若 `dispatched` 为真：`s.mouseReleased(ev)`，`activatedVia="screen-dispatch"`；
5. `clicked = handled`。

**为什么必须补 `onClick`**：只调 `Screen.mouseClicked(...)` 会返回 `true`（于是 `dispatched:true`），但界面不动。
补上 `AbstractWidget.onClick(ev,false)` 之后 `TitleScreen` → `OptionsScreen` 才真的跳转。
所以断言别只看 `clicked:true`——要看 **`screen` 变了**，或 `activatedVia` 是你预期的路径。

边界：没有界面时不是 4xx，而是 `200 {"clicked":false,"reason":"no screen is showing"}`；
`x`/`y` 缺失或不是数字才 400（`x and y query params required (doubles)`）。

## 4. 另外三个 UI 端点

| 端点 | 实际行为 | 返回 |
|---|---|---|
| `/ui/hover?x=&y=` | `s.mouseMoved(x,y)` | `hovered:true`；无界面 `hovered:false` + `reason` |
| `/ui/close` | `s.onClose()` | `closed:true`（实测 `AccessibilityOnboardingScreen` → `TitleScreen`）；无界面 `closed:false` + `reason` |
| `/ui/hover_slot?x=&y=` | 容器界面才做：`menu` + `slots` 数 + `hoverDispatched:true` | `hoveredSlot` **永远报不出第 N 格**：`AbstractContainerScreen.hoveredSlot` 是 `protected` 且无 accessor，`leftPos/topPos` 同样 protected ⇒ 用 `"unavailable: ..."` 字段自述，而不是假装成功或回 501 |

`/ui/hover_slot` 的这条限制是**已接受的缺口**（不做反射、不做坐标反推）。要验「某格里有东西」请读
`/player/inventory`（`{"opened":true,"menu":"InventoryMenu","slots":46}`），它不依赖 GUI。

## 5. `/screenshot`

```
GET /screenshot → {"file":"2026-10-19_12.49.54.png","size":316468,"width":854,"height":480}
```

- 26.3 的 `Screenshot.grab(Minecraft, boolean)` **不再接受目标文件名**，文件名由原版决定 ⇒ 想要「最新那张」
  就抓完扫目录取 mtime 最新的 png（`-KeepRunning` 时人工看的就是这个）。
- dev 窗口实测 `427×240`，截出来 `854×480`（2×）。**真实客户端窗口下的坐标映射未验证**——
  用 `/ui/click` 时坐标是按逻辑分辨率算的，别拿截图像素直接当坐标用。

## 6. 什么能自动判、什么只能人眼

- ✅ 自动判：界面是否切换（`screen` 字段）、控件是否存在/是否 active（`widgets[]`）、路由响应与字段值、
  容器 menu 名与槽位数、截图文件是否落盘。
- ❌ 只能人眼：画面好不好看、动画/过渡、光影、手感、tooltip 排版、以及**「世界是否真按动作改变了」**
  ——`/player/*` 只证明「有响应且服务端接受」，要证明世界变了必须用服务端 `/world/block` 回读。

## 7. 时序：别用 tick 当推进判据

- 就绪信号用日志行：客户端 `Mcdev Client Probe listening on 127.0.0.1:39002`。
- 连接分三级，别把第一级当成功：`{"connecting":true}`（≠进世界）→ 服务端日志 `joined the game` →
  `/status` 的 `players` 0→1 且客户端 `/status` 多出 `player` / `dimension`。
- `/connect` 幂等：进了世界再 POST 返回 `{"alreadyInWorld":true}`。
- **`frozen` 期间 tick 照涨**（实测 854→916→977），只有 `gameTime`/`dayTime` 冻住。
  `getTickCount()` 数的是主循环，`/tick freeze` 停不下它 ⇒ 拿 tick 数当"世界推进了多少"会得出错结论。
- 「click 之后等几 tick」「GUI 开/关的过渡时序」**无实测记录**：要用就写成
  `retry` 重新 `/ui/snapshot` 直到 `screen` 变成期望值，而不是 sleep 一个猜出来的毫秒数。

## 8. 未验证 / 已作废（写文档时别引）

- ⚠️ `docs/p3-client-record.md` §3 那节「`/ui/*` 降级 501、26.3 拿不到界面对象」**整节已作废**
  （2026-10-19 13:35 复查推翻）。现在的实现就是 §1-§4 描述的样子。看到「UI 自动化不可用」的旧结论直接忽略。
- 未验证：`/ui/*` 在 26.1 / 26.2 是否同样可用（若那些版本还有 `Minecraft.screen`，能力表要按版本派生）。
- 未验证：真实窗口 / 高 DPI 下的坐标映射。
- 未验证：Forge / NeoForge 客户端入口（当前只有 Fabric 客户端侧落地）。
- 已接受缺口：`/ui/hover_slot` 拿不到槽位序号。
