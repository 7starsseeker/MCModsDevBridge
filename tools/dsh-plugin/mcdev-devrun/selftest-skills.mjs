/**
 * selftest-skills.mjs — 校验 skills 知识层没坏
 *
 * 它检查四件事（都是「坏了会静默」的那类）：
 *   1. 每个登记的 skill 都有 SKILL.md，get() 能读到非空正文；
 *   2. skills/ 下有 SKILL.md 的目录与登记表**双向一致**（漏登记/多余目录都算失败）；
 *   3. SKILL.md 里引用的 references/xxx.md 真实存在（防止「文档说去看 X，X 不存在」）；
 *   4. 正文非空且以 `# ` 开头（前端按 markdown 渲染，没有一级标题的正文读起来是断的）。
 *
 * 用法：node tools\dsh-plugin\mcdev-devrun\selftest-skills.mjs  （退出码 0 = 全过）
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { registerSkills } from './skills-provider.mjs'

const HERE = dirname(fileURLToPath(import.meta.url))

let fail = 0
const ok = m => console.log(`  ok   ${m}`)
const bad = m => { fail++; console.log(`  FAIL ${m}`) }

// ---- 用一个假 ctx 抓住 provider，不依赖 DSH 运行时 ----
let provider = null
const fakeCtx = { skills: { registerProvider: f => { provider = f() } } }
const origLog = console.log
console.log('[selftest] 注册（预期下面有一行 skills registered）')
registerSkills(fakeCtx)
if (!provider) {
  origLog('FAIL: provider 没有被注册')
  process.exit(1)
}

const candidates = await provider.list()
console.log(`[selftest] provider=${provider.name} skills=${candidates.length}`)

// 1 + 4
for (const c of candidates) {
  const path = c.locator instanceof URL ? fileURLToPath(c.locator) : String(c.locator)
  if (!existsSync(path)) { bad(`${c.name}: SKILL.md 不存在（${path}）`); continue }
  const got = await provider.get(c)
  if (!got.content || got.content.trim().length < 200) bad(`${c.name}: 正文为空或过短`)
  else if (!got.content.startsWith('# ')) bad(`${c.name}: 正文没有以 '# ' 一级标题开头`)
  else ok(`${c.name}: ${got.content.length} 字符`)
  if (got.resourceBase?.kind !== 'directory') bad(`${c.name}: resourceBase 不是 directory`)
}

// 2 双向一致
const onDisk = readdirSync(join(HERE, 'skills'))
  .filter(d => existsSync(join(HERE, 'skills', d, 'SKILL.md')))
const registered = new Set(candidates.map(c => c.name))
const missing = [...registered].filter(n => !onDisk.includes(n))
const orphan = onDisk.filter(n => !registered.has(n))
if (missing.length) bad(`登记了但没有目录: ${missing.join(', ')}`)
if (orphan.length) bad(`有目录但没登记: ${orphan.join(', ')}`)
if (!missing.length && !orphan.length) ok(`登记表与 skills/ 双向一致（${onDisk.length} 个）`)

// 3 references 引用可达
for (const c of candidates) {
  const dir = join(HERE, 'skills', c.name)
  const body = readFileSync(join(dir, 'SKILL.md'), 'utf8')
  const refs = [...body.matchAll(/references\/[A-Za-z0-9._-]+\.md/g)].map(m => m[0])
  for (const r of new Set(refs)) {
    if (existsSync(join(dir, r))) ok(`${c.name}: 引用可达 ${r}`)
    else bad(`${c.name}: 引用了不存在的 ${r}`)
  }
}

console.log(fail === 0 ? '\n[selftest] PASS' : `\n[selftest] FAIL (${fail})`)
process.exit(fail === 0 ? 0 : 1)
