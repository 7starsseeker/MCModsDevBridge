# 数据驱动内容怎么验：配方 / 合成 / 掉落（mc-content-data-verification）

给「我加了一个配方 / 改了掉落表 / 加了物品，怎么证明它真的对」用的。命中词：配方、合成、craft、recipe、
loot、掉落、物品、tags、材料、产出、背包满、掉地上。

**先读这四条的边界，再写断言**——这套端点能证明的比你以为的少，但少的那部分反而最容易骗过你。

## 1. `/recipe` 只能证明「配方存在且产物是什么」

```
GET /recipe?id=minecraft:oak_planks
→ { found:true, item:"minecraft:oak_planks", count:4 }
```

- 返回 `{found, item, count}`。产物取的是 `recipe.display()` 第 0 个 slot 的**第一个 stack**。
- **两条失败路径形状不同**：id 写错 → `found:false` + `error:"invalid recipe id"`；
  **配方不存在 → 只有 `found:false`，完全没有 `error` 字段**。
  所以断言写 `equals: { "data.found": true }`，不要写 `error` 相关的东西——不存在时它不出现。
- 一个配方有多个 display（比如燃料、不同变体）时，这里只给你第 0 个。要验「所有变体」得自己逐条查。

## 2. `/craft` 是「按材料扣物品换产物」，不是真的合成

```
POST /craft?recipe=minecraft:oak_planks&player=Dev
→ { found:true, crafted:true, consumed:[{item:"minecraft:oak_log",count:1}], missing:[], result:{item:"minecraft:oak_planks",count:4} }
```

它做的事：查配方 → 遍历 `recipe.placementInfo().ingredients()` → **从玩家背包里按顺序 `shrink(1)`**
→ 全部匹配上才把产物塞进背包（塞不下就 `drop` 到脚下）。

因此：

| 它**不会**做 | 后果 |
|---|---|
| 校验网格形状 / 摆位 | 你的配方写成了 2×2 却声称 3×3，`/craft` 照样「成功」 |
| 跑 recipe remainder（桶还给你、工具掉耐久） | 带容器的配方在这里会凭空少一个桶，**不是 bug 而是这套端点不演练它** |
| 触发 craft 统计 / 合成事件 / 成就 | 「合成后触发 advancement」这类需求验不出来 |
| 检查配方书是否解锁 | 玩家没解锁也照样 craft |

**材料按 ingredients 顺序扣，不按你的摆法扣**。校验「配方定义对不对」要看数据文件；
`/craft` 验的是「这个 id + 这些材料 → 这个产物」这条链路。

### 失败时已经扣掉的材料**不会退回**

三条失败分支里，`missing` 非空那条最阴：它是在**循环里已经 shrink 完之后**才返回 `crafted:false`。也就是说
材料够一半时，你丢了那一半、什么也没得到。**给材料的断言要放在 craft 之前**，失败后重跑前先补材料。

三条失败分支各自长这样：

- 玩家不在线 → `found:false, crafted:false, error:"player not online"`
- recipe id 非法 → `found:false, crafted:false, error:"invalid recipe id"`
- 配方不存在 → `found:false, crafted:false, error:"recipe not found"`

注意和 `/recipe` 的差异：**这里配方不存在是有 `error` 的**。同一个「不存在」，两个端点两种形状——别照抄。

### 背包满 ⇒ 产物掉在地上

`add()` 失败时走 `player.drop(..., Prediction.SERVER_ONLY)`，产物变成 `ItemEntity`。所以「`crafted:true` 但背包里没有」
不是 bug，用 `/items` 在脚下找。

## 3. `/loot` 只解默认状态、固定主世界、不吃工具

```
GET /loot?block=minecraft:diamond_ore&x=0&y=64&z=0
→ { found:true, drops:[{item:"minecraft:diamond",count:1}], total:1 }
```

底层是 `Block.getDrops(block.defaultBlockState(), overworld, pos, null, null, ItemStack.EMPTY)`：

- **只有默认状态**：你测 `age=7` 的小麦，拿到的是 `age=0` 的掉落。
- **只有主世界**（`dim` 参数不存在），下界石英矿之类在这里按主世界算。
- `tool=null` ⇒ **没有时运、没有精准采集、没有剪刀**；「时运 III 出 3 个」验不出来。
- 方块不存在 → `found:false`（无 error）。

它适合验「这个方块原本掉什么」，不适合验「用某把工具挖它掉什么」。后者用
`/world/mine`（真破坏 + 真掉落）再 `/items` 回读。

## 4. `/items` 是附近掉落物，不是物品注册表

```
GET /items?x=0&y=64&z=0&r=4&dim=minecraft:overworld
→ { pos:"0,64,0", radius:4, drops:[{item:"minecraft:oak_planks",count:4}], total:4 }
```

`r` 被夹在 1..16。这是**验证产物/掉落入袋之前的中间态**的端点：合成溢出、挖方块、`/action/place` 失败掉的物品，
都在这。别拿它当「查一个物品存不存在」用——`/item` 也不是（那是 HUD 条渲染数据：`visible`/`width`/`color`）。

## 5. 一条完整的内容验证链

```jsonc
// 1) 配方定义活着、产物对
{ "id": "recipe-known", "target": "server", "path": "/recipe?id=yourmod:widget",
  "expect": { "equals": { "data.found": true }, "contains": { "data.item": "yourmod:widget" } } }

// 2) 给材料（破坏幂等：先 clear 再 give）
{ "id": "give-mats", "target": "server", "path": "/action/inventory",
  "body": { "op": "give", "item": "yourmod:part", "count": 2 }, "expect": { "status": 200 } }

// 3) 合成成功且消耗对
{ "id": "craft", "target": "server", "path": "/craft?recipe=yourmod:widget&player={{player}}",
  "expect": { "equals": { "data.crafted": true }, "contains": { "data.result.item": "yourmod:widget" } } }

// 4) 产物真在背包里（不是只回了 ok）
{ "id": "in-inventory", "target": "server", "path": "/player/inventory",
  "expect": { "contains": { "data.items": "yourmod:widget" } } }
```

第 4 步是必要的：`crafted:true` 只说明 `add()` 或 `drop()` 被调用了。

## 6. 验不了的，别硬验

- 配方在配方书里显示成什么样 —— GUI，人眼。
- 标签（tags）本身：探针没有 tag 查询端点。间接验法是「某物品能被某配方/某方块接受」，绕得远；
  真要验就写游戏内测试（test 框架/自定义命令）。
- 自定义配方类型（`RecipeType`）的**特殊执行逻辑**：`/craft` 走的是 ingredients 抠物品，不执行你的
  `assemble()` 逻辑之外的东西；自定义逻辑要自己暴露一个可读状态（方块实体 / 命令输出）再断言。
