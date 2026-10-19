# P3 端差异 —— 客户端探针移植实测记录

> 实测日期：2026-10-19 12:03–12:51
> 验收平台：Fabric 26.3（客户端 + 专用服）
> 前置：[p2-adaptation-record.md](p2-adaptation-record.md)、[p1-port-record.md](p1-port-record.md)

---

## 0. 一句话结论

**客户端探针移植成立；`/ui/*` 五条曾经判为「必须降级」，2026-10-19 13:35 复查后已全部实现（见文末「§3 更正」）。**
17 条客户端路由**当时实测 12×200 + 5×501（按设计降级）**；补实现后为
**15×200 + 1×400（缺参）+ 1×409（无目标），无 501**。`/status` 返回 `side=client` +
`capabilities:["core","net","inventory","command","player_action","client_ui"]`，
`/connect` 把客户端送进本地 Fabric 专用服（服务端 `/status` 的 `players` 从 0 变 1）。

---

## 1. 端边界怎么落地的

### 1.1 源码布局

```
lab/shared/src/main/java/com/mcdev/probe/       ← 服务端侧，三份骨架都包含
lab/shared/src/client/java/com/mcdev/probe/     ← 客户端侧，只有 fabric 包含
```

`lab/fabric/build.gradle`：

```gradle
loom { splitEnvironmentSourceSets() }        // 骨架自带，main = 服务端侧，client = 客户端侧
sourceSets.main.java.srcDirs   += file('../shared/src/main/java')
sourceSets.client.java.srcDirs += file('../shared/src/client/java')
```

**这条边界是 loom 强制的**：`src/client/java` 里的类在服务端 run 下不可达，反过来也成立。
不需要 mixin，不需要反射，不需要 `@Environment`。

### 1.2 为什么客户端耦合是零

扫 `ClientProbeServer.java` 的 import：11 个 `net.minecraft.client.*`，
**`net.minecraftforge.client.*` / `net.neoforged.neoforge.client.*` / `net.fabricmc.*` 全 0 处**。

⇒ 客户端探针不需要任何加载器 API。三家共用一份 `ClientProbeServer`，
只有入口（`ClientModInitializer` / `@Mod` + `FMLEnvironment.dist == Dist.CLIENT`）分叉。

### 1.3 共享库新增的注册表设施

`ProbeServer` 与 `ClientProbeServer` 各加一个 `route(path, handler, trait)` 私有静态方法，
把原来 15/17 行裸 `server.createContext()` 收成一行一 trait；`/status` 的 `capabilities`
由这张表**派生**（不手写，避免与实际路由不一致）。

| 端 | capabilities |
|---|---|
| 服务端 | `core` `command` `world` `inventory` `craft` |
| 客户端 | `core` `net` `inventory` `command` `player_action` `client_ui` |

---

## 2. 26.3 客户端 API 的破坏性改动（实测清单）

`ClientProbeServer` 从 1.20.1 迁到 26.3，编译期报 **48 个错误**，归并为 11 类根因：

| # | 1.20.1 写法 | 26.3 现状 | 处理 |
|---|---|---|---|
| 1 | `Minecraft.screen`（字段） | **整个字段消失**（359 个公开成员里既无该字段也无 getter），只剩 `setScreenAndShow(Screen)` | `/ui/*` 降级（见 §3） |
| 2 | `mc.setScreen(s)` | 删除，改 `setScreenAndShow(s)` | 同上 |
| 3 | `Screen.mouseClicked(double,double,int)` | `GuiEventListener.mouseClicked(MouseButtonEvent, boolean)`；`MouseButtonEvent(double,double,MouseButtonInfo)`、`MouseButtonInfo(int button,int modifiers)` | 同上 |
| 4 | `AbstractContainerScreen.getGuiLeft()/getGuiTop()` | 删除，改 protected 字段 `leftPos` / `topPos` | 不需要（降级后无用） |
| 5 | `Window.getWindow()` 取 HWND | 删除；`Window.handle()` 返回 `long`；26.3 用 **SDL**（`org.lwjgl.sdl.SDL_Event`）而非 GLFW | 不需要（降级后无用） |
| 6 | `ItemStack.getTag()` | 删除（组件化） | `/item/mainhand` 只报 count + 耐久条，不报 NBT |
| 7 | `Minecraft.getMainRenderTarget()` | 删除，改 GPU 表面抽象（`mc.windowSurface()`） | 不需要 |
| 8 | `Screenshot.grab(File,String,RenderTarget,int,Consumer)` | `Screenshot.grab(Minecraft, boolean)`（文件命名由原版决定） | 改为「抓完后扫目录找最新 png」 |
| 9 | `ClickType` | 改名 `ContainerInput`（`net.minecraft.world.inventory.ContainerInput`） | 直接换名 |
| 10 | `new ServerData(name, ip, false)` | `ServerData(String, String, ServerData$Type)`（`LAN` / `REALM` / `OTHER`） | 传 `ServerData.Type.OTHER` |
| 11 | `ConnectScreen.startConnecting(parent, mc, address, data, hasQuickPlay)` | 6 参：`(Screen, Minecraft, ServerAddress, ServerData, boolean, TransferState)` | 末参传 `null` |

**存活未变的**：`mc.player` / `mc.gameMode` / `mc.level` / `mc.hitResult` / `mc.getFps()` /
`mc.getConnection()` / `mc.gameDirectory` / `mc.options` —— 这些占了客户端探针的大部分行数。

---

## 3. ~~降级决策：5 条 `/ui/*` → 501~~ ⚠️ **本节结论已作废**（2026-10-19 13:35 实测推翻）

> 保留原文作历史记录。真相见文末「§3 更正」。

### 3.1 为什么降级

`Minecraft.screen` 被整个删掉 ⇒ **没有任何公开 API 能拿到「当前显示的界面对象」**。
`/ui/snapshot` `/ui/click` `/ui/hover` `/ui/hover_slot` `/ui/close` 全部依赖它。
强行用反射去摸私有字段违反 architecture §13 反模式第 1 条（反射写 `net.minecraft.*`），
且 26.x 每个 minor 都可能变，是长期负债。

### 3.2 降级形态

五条路由保留注册、保留 `client_ui` trait，但统一转 `handleUiUnavailable`，
返回 **501** + 结构化原因：

```json
{"ok":false,"error":{"code":"TRAIT_UNAVAILABLE","trait":"client_ui",
  "message":"client UI endpoints unavailable on MC 26.3: Minecraft.screen was removed"}}
```

**为什么留着而不是删掉**：能力集合要能被 DSH 插件读到（`/status` 的 `capabilities` 里
`client_ui` 仍在，但调用会 501），这样插件能给出「此版本不支持 UI 自动化」的明确提示，
而不是路由 404（404 会被误读成「路由名写错了」）。

### 3.3 `/player/inventory` 的替代实现

原来靠打开 `InventoryScreen` 后读 GUI 槽位。降级后改成**直接读 `mc.player.containerMenu`**，
返回 `{"opened":true,"menu":"InventoryMenu","slots":46}` —— 不依赖任何 GUI 对象，反而更稳。

---

## 4. 17 条客户端路由实测矩阵

环境：`gradlew runClient`（离线账号 `Player520`），客户端探针 `127.0.0.1:39002`，
专用服 `<MC_ROOT>\Server`（25566，探针 `39011`）。请求头 `X-Mcdev-Token: mcdev`。

| # | 路由 | 方法 | 结果 | 关键返回 |
|---|---|---|---|---|
| 1 | `/status` | GET | **200** | `side=client` `loader=fabric` `capabilities=[core,net,inventory,command,player_action,client_ui]` `fps` `width` `height` `player` `dimension` |
| 2 | `/log/tail` | GET | **200** | 环形缓冲末 N 行（含 DEBUG） |
| 3 | `/item/mainhand` | GET | **200** | `found:true,count:0,barVisible:false` |
| 4 | `/player/inventory` | GET | **200** | `opened:true,menu:InventoryMenu,slots:46` |
| 5 | `/command` | POST `{"cmd":"/time query gametime"}` | **200** | `sent:true` |
| 6 | `/player/use` | POST `{"x":0,"y":63,"z":-83,"face":"up"}` | **200** | `used:true,result:Pass[]` |
| 7 | `/player/use_item` | POST | **200** | `used:true,result:Pass[]` |
| 8 | `/player/break` | POST | **200** | `before:"minecraft:air",started:true,after:"minecraft:air"` |
| 9 | `/player/mine` | POST | **200** | `connection:true,broken:true,localMode:"survival"` |
| 10 | `/screenshot` | GET | **200** | `file:"2026-10-19_12.49.54.png"` `size:316468` `width:854,height:480` |
| 11 | `/ui/snapshot` | GET | **501** | 降级（§3） |
| 12 | `/ui/click` | GET | **501** | 降级 |
| 13 | `/ui/close` | GET | **501** | 降级 |
| 14 | `/ui/hover` | GET | **501** | 降级 |
| 15 | `/ui/hover_slot` | GET | **501** | 降级 |
| 16 | `/connect` | POST `?host=&port=` | **200** | 未进世界时 `connecting:true`；已进世界时 `alreadyInWorld:true` |
| 17 | `/quit` | POST | **200** | `quitting:true`（客户端进程 exit 0） |

**合计 12×200 + 5×501 = 17/17，无一失败。**

---

## 5. 端到端：客户端 → 本地专用服

| 步骤 | 证据 |
|---|---|
| 客户端启动 | 日志 `Mcdev Client Probe listening on 127.0.0.1:39002`，窗口 `Minecraft* 26.3` |
| `POST /connect?host=127.0.0.1&port=25566` | `{"ok":true,"data":{"connecting":true}}` |
| 服务端日志 | `Player520[/127.0.0.1:xxxxx] logged in with entity id 5 at (2.5, 64.0, -83.5)` / `Player520 joined the game` |
| 服务端 `/status` | `players: 0` → **`players: 1`** |
| 客户端 `/status` | 多出 `"player":"Player520","dimension":"minecraft:overworld"` |
| 再 `POST /connect` | `{"alreadyInWorld":true}`（幂等） |

### 5.1 踩到的坑（环境，不是代码）

- **`white-list=true`** 是 26.3 `server.properties` 的默认值 ⇒ 离线账号被踢
  （`Disconnecting Player791: You are not white-listed on this server!`）。测试服已改 `white-list=false`。
- **`runClient` 首次会 `downloadAssets`**，本机走代理约 190 KB/s，1.8 GB 资产要 1 小时。
  解法：把 `<HMCL_HOME>\assets\objects`（10,367 文件 / 1,873.7 MB）robocopy 进
  `~/.gradle/caches/fabric-loom/assets/objects`（索引同一份，`26.3-34.json` = 597,035 B 与 HMCL `34.json` 字节数一致），9 秒完成。
- **专用服不能用后台 job 起**：harness 的 pwsh 后台 job 关闭 stdin ⇒ MC 服务端读到 EOF 立即退出（exit 1）。
  必须 `Start-Process -WindowStyle Hidden -PassThru`（分离进程）。
- **探针鉴权是请求头** `X-Mcdev-Token`，不是 query 参数；不带头一律 401。

---

## 6. 文件与行数

| 文件 | 行数 | 说明 |
|---|---|---|
| `lab/shared/src/client/java/com/mcdev/probe/ClientProbeServer.java` | **584** | 原 788 行 → 移除 UI 交互实现后 584 |
| `lab/shared/src/main/java/com/mcdev/probe/ProbeServer.java` | **678** | P2 的 669 + `route()`/`capabilities` 收口 |
| `lab/fabric/src/client/java/com/mcdev/probe/fabric/McdevProbeClient.java` | **32** | `ClientModInitializer` + `ClientLifecycleEvents.CLIENT_STARTED/STOPPING` |
| `lab/fabric/build/libs/modid-1.0.0.jar` | 47,529 B | 含 8 个服务端 class + 2 个客户端 class |

`gradlew build` **BUILD SUCCESSFUL，0 警告**（`-Xlint:deprecation` 已开）。

---

## 7. 复现步骤

```powershell
# 1) 服务端（分离进程，别用后台 job）
Start-Process -FilePath '<JAVA_EXE>' `
  -ArgumentList '-Xms1G','-Xmx2G','-Dmcdev.probe.port=39011','-jar','fabric-26.3.jar','nogui' `
  -WorkingDirectory '<MC_ROOT>\Server' -WindowStyle Hidden -PassThru

# 2) 客户端
$env:JAVA_HOME='<JAVA_HOME>'
cd <REPO>\lab\fabric
.\gradlew.bat runClient --console=plain

# 3) 验证
$h = @{ 'X-Mcdev-Token' = 'mcdev' }
Invoke-WebRequest 'http://127.0.0.1:39001/status' -Headers $h      # side=server
Invoke-WebRequest 'http://127.0.0.1:39002/status' -Headers $h      # side=client
Invoke-WebRequest 'http://127.0.0.1:39002/connect?host=127.0.0.1&port=25566' -Method Post -Headers $h
```

---

## 8. 未做 / 未验证

1. **Forge / NeoForge 的客户端入口没有做** —— 只有 Fabric。三家的客户端入口分叉形态已定
   （Forge：`@Mod` 构造器里判 `FMLEnvironment.dist == Dist.CLIENT`；NeoForge：同构），但未落地。
2. **`/ui/*` 在 26.1/26.2 是否也这样** —— 未查。若 26.1 还有 `Minecraft.screen`，
   那 `/ui/*` 就会变成「26.1 能用、26.3 不能用」的版本相关能力，`capabilities` 要按版本派生。
3. **窗口尺寸 427×240** 是 loom 开发默认值；`/screenshot` 出的图 854×480。
   真实客户端窗口下坐标映射未验（降级后也暂时用不上）。
4. **`/player/*` 只验了「有响应且服务端接受」**，没有做「挖掉方块后世界真的变了」这类断言 ——
   那属于 P5 测试矩阵，不是 P3 的范围。

---

## §3 更正（2026-10-19 13:35，实测推翻上面的降级结论）

### 真实情况

`Minecraft` 确实不再持有 `Screen`（`screen` 字段与 getter 都没有，只剩 `setScreenAndShow(Screen)`），
**但 `Screen` 搬到了 `net.minecraft.client.gui.Gui`**：

| API | 签名（javap 实测） |
|---|---|
| `Minecraft.gui` | `public final net.minecraft.client.gui.Gui` |
| `Gui.screen()` | 返回当前 `Screen`，无界面时为 `null` |
| `Gui.setScreen(Screen)` | 切换界面 |
| `Gui.overlay()` | 返回当前 overlay（toast/调试层） |
| `Screen.children()` | `List<? extends GuiEventListener>` |
| `GuiEventListener.mouseMoved/mouseClicked/mouseReleased` | `(double,double)` / `(MouseButtonEvent, boolean)` / `(MouseButtonEvent)` |
| `MouseButtonEvent(double x, double y, MouseButtonInfo info)` | `MouseButtonInfo(int button, int modifiers)` |
| `AbstractWidget` | `getX/getY/getWidth/getHeight/getMessage/isActive/isHovered`，`onClick(MouseButtonEvent, boolean)` 是**公开**的 |

### 五条的实现与实测

| 路由 | 实现 | 实测证据 |
|---|---|---|
| `/ui/snapshot` | 读 `mc.gui.screen()` + `s.children()`，逐控件输出 type/x/y/w/h/label/active/hovered | `AccessibilityOnboardingScreen` 6 个控件；`TitleScreen` 9 个控件；`OptionsScreen` 14 个 |
| `/ui/click` | 命中控件后必须调 `AbstractWidget.onClick(ev,false)` | 只调 `Screen.mouseClicked` 返回 `true` 但界面不动；加 `onClick` 后 `TitleScreen` → `OptionsScreen` |
| `/ui/hover` | `s.mouseMoved(x, y)` | 200 + `hovered:true` |
| `/ui/close` | `s.onClose()` | `AccessibilityOnboardingScreen` → `TitleScreen` |
| `/ui/hover_slot` | `menu` 名 + `slots` 数量 + `hoverDispatched` | 200；**`hoveredSlot` 读不到**（protected 无 accessor） |

### 唯一剩下的真缺口

`AbstractContainerScreen.hoveredSlot` 是 `protected` 且没有公开访问器 ⇒ `/ui/hover_slot`
只能报「这是个容器界面 + 有多少格 + 已派发 hover」，不能报「悬停在第 N 格」。
绕法只有三条：①按坐标自己算槽位（`leftPos/topPos` 也是 protected，仍需另找公开几何）
②反射（违反架构反模式）③不做。**当前选 ③。**

### 对 capabilities 的影响

`client_ui` 不再是「声明了但调用会 501」的保留位，而是**真的可用**。
`/ui/hover_slot` 的返回体里带 `hoveredSlot: "unavailable: …"` 字段自我说明，不靠 501 表达。
