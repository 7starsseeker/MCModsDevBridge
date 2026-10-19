# 26.3 三族最小工程（mc26-mod-skeleton）

给「从零搭一个 MC 26.3 mod 工程」或「在本仓 lab/ 下加一个 fixture」用的。命中词：搭工程、脚手架、scaffold、
`build.gradle`、`gradle.properties`、loom、ModDevGradle、ForgeGradle、`fabric.mod.json`、`mods.toml`、
`neoforge.mods.toml`、split source sets、客户端源集、`runClient` 起不来、Java 25。

一句话：**三条线是三套构建体系**，不要指望一份 `build.gradle` 通吃；26.3 要求 **Java 25**，这不是可选项。

## 1. 版本事实表（2026-10 本仓实测）

| 项 | Fabric | Forge | NeoForge |
|---|---|---|---|
| Minecraft | `26.3` | `26.3` | `26.3`（`minecraft_version_range=[26.3]`） |
| 构建插件 | `net.fabricmc.fabric-loom` **1.18-SNAPSHOT** | `net.minecraftforge.gradle` **[7.0.17,8)** | ModDevGradle **2.0.148** |
| 加载器 | fabric-loader **0.19.5** | forge **26.3-66.0.13** | neoforge **26.3.0.88** |
| API 依赖 | fabric-api **0.163.0+26.3**（**硬依赖**，少了起不来） | 无 | 无 |
| 额外 | — | `annotationProcessor 'net.minecraftforge:eventbus-validator:7.0.6'` | — |
| Java | `options.release = 25` | `toolchain 25` | `languageVersion = 25` |
| mod 元数据 | `src/main/resources/fabric.mod.json` | `src/main/resources/META-INF/mods.toml` | `src/main/templates/META-INF/neoforge.mods.toml`（走 `processResources` 展开变量） |
| 入口声明 | `fabric.mod.json` 的 `entrypoints.client/server` | `@Mod` 注解 + `META-INF/mods.toml` | `@Mod("mod_id")` + toml 的 `mod_id` |
| 客户端源集 | loom `splitEnvironmentSourceSets()` → `sourceSets.client` | 无独立源集（`merge-source-sets=true`） | 无独立源集 |

`provision-instance` 的服务端 loader 默认值（fabric 0.20.0）与 mod 工程的 `loader_version`（0.19.5）是两回事，
别互抄——前者是服务端 launcher，后者是编译期 API。

## 2. Fabric：最小 `build.gradle` 的七条

```groovy
plugins { id 'net.fabricmc.fabric-loom' version "${loom_version}" }
loom { splitEnvironmentSourceSets(); mods { "my_mod" { sourceSet sourceSets.main; sourceSet sourceSets.client } } }
dependencies {
  minecraft "com.mojang:minecraft:${project.minecraft_version}"
  implementation "net.fabricmc:fabric-loader:${project.loader_version}"
  implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"
}
base { archivesName = 'my_mod' }          // 产物名；不写就是工程目录名
tasks.withType(JavaCompile).configureEach { it.options.release = 25 }
java { sourceCompatibility = JavaVersion.VERSION_25; targetCompatibility = JavaVersion.VERSION_25 }
processResources { filesMatching("fabric.mod.json") { expand "version": project.version } }
```

`splitEnvironmentSourceSets()` 是**26.3 这条线的工作方式**，不是风格偏好：客户端类只在 `src/client/java` 可见，
主源集碰不到它。副作用是 `mods { }` 里必须**同时列 `main` 和 `client` 两个源集**，漏一个 → `runClient` 找不到入口。

`fabric.mod.json` 的 `depends` 四行都不能省：

```json
{ "depends": { "fabricloader": ">=0.19.5", "minecraft": "~26.3", "java": ">=25", "fabric-api": "*" },
  "entrypoints": { "client": ["com.example.MyClient"], "server": ["com.example.MyMod"] } }
```

## 3. Forge / NeoForge：一句话差异

- **Forge**：FG 7 起是 `minecraft { }` + `minecraft.mavenizer(it)` + `maven fg.minecraftLibsMaven`，
  依赖写成 `implementation minecraft.dependency('net.minecraftforge:forge:26.3-66.0.13')`；
  `mods.toml` 放 `resources/META-INF/`，不进 templates。
- **NeoForge**：用 ModDevGradle，`gradle.properties` 里 `minecraft_version` / `minecraft_version_range` /
  `neo_version` / `mod_id` / `mod_name` / `mod_version` / `mod_group_id` 是模板变量；
  toml 放 `src/main/templates/`，靠 `processResources` 展开。
  **`mod_id` 必须等于 `@Mod("...")` 里的字符串常量**（小写，`[a-z][a-z0-9_]{1,63}`），不一致时游戏内找不到 mod，
  报错还很不直观。
- 两家都没有 fabric 那种「客户端源集」：客户端专用代码和公共代码在同一源集里，靠 `DistExecutor` /
  `@Mod(value=..., dist=Dist.CLIENT)` 之类隔离。**因此 Forge/NeoForge 工程里引用了客户端类型的类，
  别放在任何会被专用服务器加载到的路径上**——这是 `NoClassDefFoundError` 的经典来源。

## 4. 一份源码三个加载器（本仓的做法）

不想维护三份重复代码时，把公共逻辑放 `shared/src/main/java`，客户端部分放 `shared/src/client/java`，
然后每个工程各加两行：

```groovy
sourceSets.main.java.srcDirs   += file('../shared/src/main/java')     // fabric 两个源集都要加
sourceSets.client.java.srcDirs += file('../shared/src/client/java')   // 只有开了 split 的工程有 sourceSets.client
```

Forge/NeoForge 没有 `sourceSets.client`，只需把客户端那部分也塞进 `main`（它们本来就不分源集）。
代价：一旦某家的 API 差异大到不能用同一份代码，就要开始写 `//? if fabric` 这类条件编译——**别提前上**，
三份骨架先各自能跑，再考虑合并。

## 5. 常见错法

| 症状 | 大概率原因 |
|---|---|
| `runClient` 起不来 / 找不到主类 | `fabric.mod.json` 的 `entrypoints` 写了但类不在对应源集；或 `mods { }` 漏了 `sourceSets.client` |
| 专用服务器上 `NoClassDefFoundError: net/minecraft/client/...` | 客户端类混进了公共/服务端路径（Forge/NeoForge 尤其容易） |
| Fabric 客户端一启动就崩，提示缺 `fabric-api` | `depends` 少写 `"fabric-api": "*"`，或运行时 `mods/` 里真没放 |
| NeoForge 游戏里看不到 mod | `mod_id` 与 `@Mod` 常量不一致（大小写/下划线都算） |
| 编译报 `invalid source release: 25` | Gradle 用的 JDK 不是 25（`java.toolchain` 只约束编译目标，不换 JDK 本身） |
| 改了 Java 代码行为没变 | 增量构建残留；判「没生效」前先全量构建一次（`[推断]`：Forge 的 `runClient` 与 `build` 共用输出目录） |

## 6. 边界

- 本文件只覆盖 **26.3 + Java 25**。1.20.1 及更早的工程结构（FG 5/6、loom 1.x 早期、Yarn 映射）
  完全不同，别把这里的结论套过去。
- 「三族最小工程」的完整可编译文件在本仓 `lab/fabric`、`lab/forge`、`lab/neoforge`（**仓库版**才有；
  发布 zip 不含源码工程，只含探针 jar + 脚本）。要照抄就去仓库拿，别凭记忆重写这三份 `build.gradle`。
