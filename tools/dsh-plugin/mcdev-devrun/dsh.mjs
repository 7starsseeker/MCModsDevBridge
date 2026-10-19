/**
 * mcdev-devrun/dsh.mjs — 把 MC 探针的端到端编排脚本包成两个 DSH 工具
 *
 * ── 它解决什么 ───────────────────────────────────────────────────────────────
 * `scripts/devrun.ps1` 已经能一条命令完成「起服 → 起客户端 → 连服+op → 执行场景 → 取证 → 收尾」，
 * 但模型要用它就得自己拼 `pwsh -NoProfile -File ... -Family ... -Scenario ...`，还要自己记住：
 *   · 三家不能并行（客户端探针端口 39002 共用）；
 *   · `-Family all` 拒绝 -EvidenceDir/-KeepRunning；
 *   · 输出很长、真正要看的是最后几行的 `PASS=/FAIL=total=` 和取证目录。
 * 这两个工具只是那一层的**薄包装**：不复制逻辑，脚本仍是唯一实现（改脚本不需要改插件）。
 *
 * ── 为什么是「薄」的 ────────────────────────────────────────────────────────
 * 编排的难点（端口抢占、句柄继承、玩家名每次变、世界残留）全在 ps1 里解决了，插件再实现一遍
 * 就会分叉。这里只做三件事：参数校验 → spawn pwsh → 把输出裁成「结论 + 尾巴 + 取证路径」。
 *
 * ── 依赖 ─────────────────────────────────────────────────────────────────────
 * · `pwsh` 在 PATH（本机 PowerShell 7）；
 * · 工作区根目录：安装时写入的 `mcdev-root.txt` → `MCDEV_ROOT` → 从 `exec.cwd` 往上找
 *   带 `scripts/devrun.ps1` 的那一级 —— 三种来源都失败就明确报错，不猜。
 *
 * ── 关掉 / 回滚 ──────────────────────────────────────────────────────────────
 * · 临时关：`$DSH_HOME/cordis.patch.yml` 里给这段 `- insert:` 加 `disabled: true`，重启 DSH。
 * · 彻底回滚：删掉 home 层那段 `- insert:`（或整份插件目录），重启 DSH。
 * · 自证：注册成功时日志打 `[mcdev-devrun] tools registered`。
 */

import { spawn } from 'node:child_process'
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { registerSkills } from './skills-provider.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))

export const name = 'mcdev-devrun'
/**
 * `tools` 常驻；`skills` 是可选的（在 apply 里用 `ctx.inject(['skills'], ...)` 取，缺席就只是没有知识层）。
 * 不碰 shell / 编码 / 配置。
 */
export const inject = ['tools']

const log = (...a) => console.log('[mcdev-devrun]', ...a)

/**
 * 自证文件：插件被 DSH 真正加载并注册成功时写一份 applied.json（旁边放 pid + 时间 + 工具名）。
 *
 * 动机与 home 层 `win-encoding` 插件同一个：**插件文件存在 ≠ 插件被加载**。DSH 只在启动时读配置，
 * 而「会话里看不到工具」还可能是会话工具表没刷新（旧会话冻结）—— 没有这个文件就只能靠猜。
 * 写完用 `Get-Content <插件目录>\applied.json` 一眼判断：有 = 注册成功，没有 = 没被加载/注册时炸了。
 *
 * 任何失败都只记日志、绝不影响加载：写文件是诊断，不是功能。
 */
function attest(toolNames) {
  try {
    const dir = process.env.DSH_HOME ? join(process.env.DSH_HOME, 'plugins', 'mcdev-devrun') : HERE
    const record = {
      plugin: name,
      pid: process.pid,
      at: new Date().toISOString(),
      tools: toolNames,
      node: process.version,
    }
    writeFileSync(join(dir, 'applied.json'), JSON.stringify(record, null, 2), 'utf8')
  } catch (e) {
    log('applied.json 写入失败（不影响加载）:', String(e?.message ?? e))
  }
}

/** 安装时由 install-dsh-plugin.ps1 写入的工作区根；不随仓库分发，换机器重装即刷新。 */
function readStamp() {
  try {
    const p = join(dirname(fileURLToPath(import.meta.url)), 'mcdev-root.txt')
    if (!existsSync(p)) return null
    const v = readFileSync(p, 'utf8').trim()
    return v || null
  } catch {
    return null
  }
}

/** 找工作区根：安装时写入的 mcdev-root.txt → env(MCDEV_ROOT) → 从 cwd 往上找。 */
function resolveRoot(cwd) {
  const stamped = readStamp()
  if (stamped && existsSync(join(stamped, 'scripts', 'devrun.ps1'))) return stamped
  const fromEnv = process.env.MCDEV_ROOT
  if (fromEnv && existsSync(join(fromEnv, 'scripts', 'devrun.ps1'))) return fromEnv
  let dir = cwd || process.cwd()
  for (let i = 0; i < 8; i++) {
    if (existsSync(join(dir, 'scripts', 'devrun.ps1'))) return dir
    const parent = join(dir, '..')
    if (parent === dir) break
    dir = parent
  }
  throw new Error(
    '找不到 MCModsDevBridge 工作区（需要 scripts/devrun.ps1）。' +
      '设置 MCDEV_ROOT 指向工作区根，或把工具的 cwd 设到工作区内的某个目录。',
  )
}

const tail = (s, n = 6000) => (s.length > n ? '…' + s.slice(-n) : s)

/** 跑 pwsh 脚本。永不抛异常（超时/退出码都进返回值），由调用方决定怎么说。 */
function runPs1(scriptPath, args, { cwd, timeoutMs, onLine } = {}) {
  return new Promise((resolveP) => {
    const child = spawn(
      'pwsh.exe',
      ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', scriptPath, ...args],
      { cwd, windowsHide: true, env: { ...process.env } },
    )
    let out = ''
    let err = ''
    let timedOut = false
    const timer = setTimeout(() => {
      timedOut = true
      child.kill()
    }, timeoutMs)
    const feed = (chunk, sink) => {
      const text = chunk.toString('utf8')
      if (sink === 'out') out += text
      else err += text
      if (onLine) for (const line of text.split(/\r?\n/)) if (line.trim()) onLine(line)
    }
    child.stdout.on('data', (c) => feed(c, 'out'))
    child.stderr.on('data', (c) => feed(c, 'err'))
    child.on('error', (e) => {
      clearTimeout(timer)
      resolveP({ code: -1, out, err: String(e?.message ?? e), timedOut })
    })
    child.on('close', (code) => {
      clearTimeout(timer)
      resolveP({ code, out, err, timedOut })
    })
  })
}

/** 从脚本输出/取证文件里抽出 `PASS=x FAIL=y total=z` 与取证目录。 */
function extractVerdict(out) {
  const verdict = (out.match(/PASS=\d+\s+FAIL=\d+\s+total=\d+/) || [])[0] || null
  const dirs = [...out.matchAll(/(?:取证：|matrix.*?evidence)\s*([A-Za-z]:\\[^\s|]+)/g)].map((m) => m[1])
  return { verdict, evidence: dirs.length ? dirs[dirs.length - 1] : null }
}

/** 读 summary.md 的开头几行（结论行 + 玩家 + 端口），给「不想要几千行输出」的调用方用。 */
function summarizeEvidence(dir) {
  if (!dir) return ''
  const f = join(dir, 'summary.md')
  if (!existsSync(f)) return ''
  const lines = readFileSync(f, 'utf8').split(/\r?\n/)
  return lines.slice(0, 6).join('\n')
}

const asText = {
  output: {
    schema: { type: 'string' },
    render: (_a, v) => [{ type: 'text', text: String(v) }],
  },
}

export function apply(ctx) {
  // 知识层：把「只有真机才知道」的开发知识注册成 skill（skills/ 目录，见 skills-provider.mjs）。
  // 用 ctx.inject 而不是写进顶层 inject —— skills 服务缺席时只是没有知识层，工具照常注册。
  ctx.inject(['skills'], (skillCtx) => registerSkills(skillCtx))

  ctx.inject(['tools'], (toolCtx) => {
    toolCtx.tools.register({
      ...asText,
      name: 'mcdev_devrun',
      description:
        '一条命令跑 MC 探针的端到端编排：起测试服 → 起客户端(runClient) → 连服+op → 执行场景 JSON → ' +
        '取证 → 收尾。三家加载器各自独立的服目录/端口；`-Family all` 串行跑三家并汇总（客户端探针端口 ' +
        '39002 共用，不能并行）。场景里可用 {{player}} 占位符和 waitMs。返回结论行 + 输出尾巴 + 取证目录。' +
        '这是 scripts/devrun.ps1 的包装，逻辑不在插件里。',
      parameters: {
        type: 'object',
        properties: {
          family: {
            type: 'string',
            enum: ['fabric', 'forge', 'neoforge', 'all'],
            description: '哪个加载器；all = 三家串行矩阵。默认 fabric。',
          },
          scenario: {
            type: 'string',
            description: '场景文件（相对工作区），默认 scenarios\\smoke.json。',
          },
          mods: {
            type: 'array',
            items: { type: 'string' },
            description: '额外要塞进测试服 mods/ 的被测 mod jar 路径（矩阵模式传不进去，会被忽略）。',
          },
          keepRunning: {
            type: 'boolean',
            description: 'true = 跑完不收尾（留服和客户端给人工排障）。矩阵模式不支持。',
          },
          timeoutMin: {
            type: 'number',
            description: '总超时（分钟），默认 single=15 / all=45。超时会杀进程并如实报告。',
          },
        },
      },
      async execute(args, exec) {
        const root = resolveRoot(exec?.cwd)
        const family = String(args?.family ?? 'fabric')
        const scenario = String(args?.scenario ?? 'scenarios\\smoke.json')
        const timeoutMin = Number(args?.timeoutMin) || (family === 'all' ? 45 : 15)
        const ps1 = join(root, 'scripts', 'devrun.ps1')
        const argv = ['-Family', family, '-Scenario', scenario]
        if (Array.isArray(args?.mods) && args.mods.length && family !== 'all') {
          argv.push('-Mods', args.mods.join(','))
        }
        if (args?.keepRunning && family !== 'all') argv.push('-KeepRunning')

        log(`devrun ${family} ${scenario} (timeout ${timeoutMin}m)`)
        const res = await runPs1(ps1, argv, { cwd: root, timeoutMs: timeoutMin * 60_000 })
        const { verdict, evidence } = extractVerdict(res.out)
        const parts = []
        parts.push(`devrun ${family} → ${verdict ?? '(no summary line)'}${res.timedOut ? ' [TIMEOUT]' : ''}`)
        if (evidence) parts.push(`evidence: ${evidence}`)
        const head = summarizeEvidence(evidence)
        if (head) parts.push('--- summary.md ---\n' + head)
        parts.push('--- output tail ---\n' + tail(res.out.trim() || '(no stdout)'))
        if (res.err.trim()) parts.push('[stderr]\n' + tail(res.err.trim(), 3000))
        if (!res.timedOut && res.code !== 0) parts.push(`[exit ${res.code}]`)
        return parts.join('\n')
      },
    })

    toolCtx.tools.register({
      ...asText,
      name: 'mcdev_scenarios',
      description:
        '列出 MC 探针端到端编排可用的场景文件（scenarios/*.json）及其名称、说明、步骤数。' +
        '写新场景前先用它看现有格式与命名。',
      parameters: { type: 'object', properties: {} },
      async execute(_args, exec) {
        const root = resolveRoot(exec?.cwd)
        const dir = join(root, 'scenarios')
        if (!existsSync(dir)) return `没有 scenarios 目录：${dir}`
        const rows = []
        for (const entry of readdirSync(dir).filter((f) => f.endsWith('.json')).sort()) {
          const full = join(dir, entry)
          try {
            const sc = JSON.parse(readFileSync(full, 'utf8'))
            rows.push(
              `- ${entry} — ${sc.name ?? '(unnamed)'}，${Array.isArray(sc.steps) ? sc.steps.length : 0} 步` +
                (sc.description ? `\n    ${sc.description}` : ''),
            )
          } catch (e) {
            rows.push(`- ${entry} — 解析失败：${e?.message ?? e}`)
          }
        }
        return `工作区：${root}\n${rows.join('\n')}`
      },
    })

    log('tools registered')
    attest(['mcdev_devrun', 'mcdev_scenarios'])
  })
}
