/**
 * skills-provider.mjs — 把本套件的「真机知识」注册成 DSH skill
 *
 * ── 它解决什么 ───────────────────────────────────────────────────────────────
 * `mcdev_devrun` 工具解决「跑得动」，但模型在**写场景之前**需要知道：探针有哪些端点、
 * 断言怎么表达、26.3 上哪些 API 已经搬走/静默失败。这些知识原来散在 docs/ 与源码注释里，
 * 只有读过这套仓的人才有。注册成 skill 之后，任何用这套件的会话在命中触发词时自动获得它。
 *
 * ── 为什么是 skill 而不是塞进工具 description ────────────────────────────────
 * 工具 description 每次请求都进 context（常驻成本）；skill 只在命中时加载（按需成本）。
 * 这里的正文有几十 KB，塞进工具描述等于每轮都付账。
 *
 * ── 依赖 ─────────────────────────────────────────────────────────────────────
 * 只有 `skills` 服务（由 dsh 自带的 dsh-skill 提供）。**刻意不 import `@deepseek-ai/dsh-skill`**：
 * 插件目录没有 package.json，import 裸包名要额外解析路径；rank 只需与它的
 * BUNDLED_SKILL_RANK(600) 常量对齐，写死一个数字比多一条解析路径可靠。
 *
 * ── 自证 ─────────────────────────────────────────────────────────────────────
 * 注册成功打 `[mcdev-devrun] skills registered (N)`；skills/ 下有目录但没登记（或反之）会打 warn
 * —— 静默少一个 skill 比报错更难查。
 */

import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const SKILLS_DIR = join(HERE, 'skills')

export const PROVIDER_NAME = 'mcdev'

/** 与 @deepseek-ai/dsh-skill 的 BUNDLED_SKILL_RANK 对齐（内置 skill 排同一档）。 */
const BUNDLED_SKILL_RANK = 600

/**
 * description 是**模型路由信号**：模型只读它来决定要不要加载正文。
 * 所以每条都要写「什么时候该用我」，而不是「我是什么」。控制在一句话内。
 */
const SKILLS = [
  {
    name: 'mcdev-probe-protocol',
    description:
      'MC 探针（服务端/客户端双端 HTTP）的端点契约：信封与错误码、X-Mcdev-Token 鉴权、用 /status 发现能力与动作、哪些端点只认 query、哪些端点打错一侧会 501、如何冻结 tick。要发探针请求或写断言前先读它，别按记忆猜路由此。',
  },
  {
    name: 'mcdev-devrun',
    description:
      '用 mcdev_devrun / scripts/devrun.ps1 跑端到端编排：六步流水线、场景 JSON 的完整语法（expect 四种断言、retry、waitMs、{{player}}、note）、证据目录怎么看、失败怎么定因、keepRunning 后如何交给人眼。要写或跑 scenario 时读它。',
  },
  {
    name: 'mc26-multiloader-traps',
    description:
      'Minecraft 26.3 上 Fabric / Forge / NeoForge 三家客户端与服务端 API 的真机陷阱清单：Screen 搬去 Gui、SDL 取代 GLFW、ClientInput.tick() 空实现、GameType.byName 静默回 SURVIVAL、创造模式发物品包不生效、player_movement_check 断线、fabric-api 硬依赖、白名单默认开。写 26.3 mod 或排查「代码看着对但不生效」时读它。',
  },
  {
    name: 'mc-verification-design',
    description:
      '把「这个功能要能用」翻译成机器可判定的步骤：四类断言的适用边界、时序该用 retry 而不是 waitMs、如何表达「只对了一半」、哪类效果只能人眼看、以及把静默失败改成响亮失败的做法。设计测试场景或审一张测试计划时读它。',
  },
  {
    name: 'mc26-mod-skeleton',
    description:
      '从零搭一个 MC 26.3 mod 工程（或在 lab/ 下加一个 fixture）：三套构建体系的版本事实表（loom 1.18-SNAPSHOT / ForgeGradle 7.0.17+ / ModDevGradle 2.0.148，loader 0.19.5 / 26.3-66.0.13 / 26.3.0.88，Java 25）、Fabric 的 split source sets 与必须列出的 client 源集、三份 mod 元数据（fabric.mod.json / resources 里的 mods.toml / templates 里的 neoforge.mods.toml）、一份 shared 源码供三家共享、客户端类放错源集导致专用服务器 NoClassDefFoundError。搭工程、改 build.gradle、runClient 起不来时读它。',
  },
  {
    name: 'mcdev-fixture-design',
    description:
      '要证明 mod 的某一侧真的生效、或者「它只装了一半」：五个 fixture 工程的装配矩阵（both / client-only / server-only / Forge / NeoForge）、故意残缺的三种做法、四层判据（双侧 /mods → 握手三行日志 → 对端自己的 missing 行作为正向证据 → active/joined/tick alive 拆开证「加载≠生效」）、同一 modId 与日志文本让同一份场景跨加载器复用、以及「Forge/NeoForge 客户端那半只过编译」这类已知边界。设计跨端测试或排查「装了但像没生效」时读它。',
  },
  {
    name: 'mc-world-assertions',
    description:
      '断言游戏世界真的变了：命令返回 ok 不算证据、setblock 的 result:0 不是成功布尔、远处未加载区块静默失败、选择器只作用于已加载实体、回读权威性四层（服务端世界 / 客户端视角 / 客户端动作 / 加载器）、位置该用 horizontalError+snappedBack 而不是单点距离、方块状态与方块实体是两次回读、单人世界证不了「只装一侧」。写方块/实体/位置类断言或排查「命令成功了但没生效」时读它。',
  },
  {
    name: 'mc-content-data-verification',
    description:
      '验配方/合成/掉落：`/recipe` 与 `/craft` 的失败形状不同（同一个「配方不存在」，一个没有 error、一个有）、`/craft` 不是真实合成（不校验网格/不跑 recipe remainder/不触发事件，材料不够时已扣的不退还，背包满就掉地上）、`/loot` 只解默认状态、固定主世界、tool=null、`/items` 是附近掉落物而不是注册表、`/item` 是 HUD 条渲染数据。写内容型断言或排查「配方在但产物不对」时读它。',
  },
  {
    name: 'mc-multiplayer-verification',
    description:
      '验多人语义：套件一次只驱动一个客户端、客户端探针 39002 共建所以矩阵必须串行，第二个客户端要手工起并换端口；`/connect` 的 body 优先于 query，已在世界时返回 alreadyInWorld 且静默不重连；「他在不在」的权威是服务端 `/player/state?player=`；白名单默认开会让人连不上看起来像网络问题；广播要断言在「另一个人」的探针上；能连上不等于 mod 功能生效。验第二个玩家/聊天/op/白名单时读它。',
  },
  {
    name: 'mc-crash-diagnosis',
    description:
      '「起不来」三分类诊断：进程没起 / 起来了但 mod 没加载（`/mods` 少 id）/ 加载了但初始化或注册炸；顺序固定 `/status` → `/mods` → `/log/tail` → 落到文件。`/log/tail` 只是 1000 行环形缓冲、且从探针装上才开始记（干净≠没异常）；「探针退到默认端口」是历史上最像崩溃的假事故；取证目录 `server.out`/`.err`/`client.out` 与实例 `logs\\latest.log`、`crash-reports\\` 的分工（后者标了 [推断]）。排查崩溃/超时/连不上探针时读它。',
  },
  {
    name: 'mcdev-instance-setup',
    description:
      '把套件部署到一台新机器/新目录并让它认得你已有的游戏实例：为什么套件不带运行时（三条打包断言）、L0/L1/L2 该建几套环境与端口约定、provision-instance 取运行时的三条路（下载/复制/离线）、只有 NeoForge maven 需要代理以及代理必须注入的四处、install-probe 为什么写配置文件而不是 -D、install-mod 的 sha256 记账与卸载、instances/<name>.json 全字段与 client.mode 的三选一。遇到部署、换机器、实例认不到、代理/镜像/离线问题时读它。',
  },
]

function candidateOf(skill) {
  const dir = join(SKILLS_DIR, skill.name)
  return {
    name: skill.name,
    description: skill.description,
    // 两侧都可调用：模型自己看情况加载；用户也能 /<name> 手动拉。
    invocation: { modelInvocable: true, userInvocable: true },
    provider: PROVIDER_NAME,
    source: 'bundled',
    // 相对路径引用（references/*.md、脚本）由 skill 工具按这个目录解析。
    resourceBase: { kind: 'directory', path: dir },
    rank: BUNDLED_SKILL_RANK,
    locator: pathToFileURL(join(dir, 'SKILL.md')),
  }
}

/** 双向核对：登记了但没有文件 / 有文件但没有登记 —— 两种都要喊出来。 */
function auditSkillDirs(log) {
  const registered = new Set(SKILLS.map(s => s.name))
  let onDisk = []
  try {
    onDisk = readdirSync(SKILLS_DIR).filter(d => existsSync(join(SKILLS_DIR, d, 'SKILL.md')))
  } catch {
    log('warn: skills/ 目录不存在，跳过核对（skills 不会被注册）')
    return
  }
  for (const name of registered) {
    if (!onDisk.includes(name)) log(`warn: 登记了 ${name} 但 skills/${name}/SKILL.md 不存在`)
  }
  for (const name of onDisk) {
    if (!registered.has(name)) log(`warn: skills/${name}/ 存在但没在 SKILLS 表里登记（不会被注册）`)
  }
}

/**
 * 注册 skill provider。由 dsh.mjs 的 apply 用 `ctx.inject(['skills'], ...)` 调用，
 * 所以 skills 服务缺席时插件其余部分照常工作。
 */
export function registerSkills(ctx) {
  const log = (...a) => console.log('[mcdev-devrun]', ...a)
  const candidates = SKILLS.map(candidateOf)
  auditSkillDirs(log)

  ctx.skills.registerProvider(() => ({
    name: PROVIDER_NAME,
    list: () => Promise.resolve(candidates.map(c => ({ ...c }))),
    async get(candidate) {
      const path = candidate.locator instanceof URL ? fileURLToPath(candidate.locator) : String(candidate.locator)
      let content
      try {
        content = readFileSync(path, 'utf8')
      } catch (err) {
        // 响亮的失败：宁可让 skill 工具报错，也不要给模型一份空正文让它自由发挥。
        throw new Error(`[mcdev] 读不到 ${candidate.name} 的正文（${path}）：${err && err.message}`)
      }
      return {
        name: candidate.name,
        description: candidate.description,
        invocation: candidate.invocation,
        provider: candidate.provider,
        source: candidate.source,
        resourceBase: candidate.resourceBase,
        content,
      }
    },
  }))

  log(`skills registered (${candidates.length}): ${candidates.map(c => c.name).join(', ')}`)
}
