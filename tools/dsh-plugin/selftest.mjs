/**
 * 自测：不装进 DSH，也能证明插件「注册逻辑」是对的。
 *
 * 做法：动态 import dsh.mjs，用一个桩 ctx 调 apply()，把 register() 收到的工具收集起来，
 * 然后断言：工具数量、名字、description 非空、parameters 是合法 JSON Schema 形状、
 * 以及返回值的 output.render 能吐出字符串。跑：
 *
 *   node tools/dsh-plugin/selftest.mjs
 *
 * 它**不**验证 DSH 运行时集成（那要重启 DSH 才算数），只验证「代码本身不会在注册阶段炸」。
 */

import { pathToFileURL } from 'node:url'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const pluginUrl = pathToFileURL(join(HERE, 'mcdev-devrun', 'dsh.mjs')).href

let failures = 0
const check = (name, ok, detail = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail && !ok ? ' — ' + detail : ''}`)
  if (!ok) failures++
}

const mod = await import(pluginUrl)
check('导出 name', mod.name === 'mcdev-devrun', String(mod.name))
check('导出 inject 含 tools', Array.isArray(mod.inject) && mod.inject.includes('tools'), JSON.stringify(mod.inject))
check('导出 apply', typeof mod.apply === 'function')

// 桩 ctx：只实现 inject(name, cb)，把 tools.register 收进数组。
const registered = []
const toolCtx = { tools: { register: (t) => registered.push(t) } }
const ctx = { inject: (names, cb) => { if (names.includes('tools')) cb(toolCtx) } }

mod.apply(ctx)
check('注册了两个工具', registered.length === 2, `实际 ${registered.length}`)

const names = registered.map((t) => t.name).sort()
check('工具名是 mcdev_devrun / mcdev_scenarios', JSON.stringify(names) === JSON.stringify(['mcdev_devrun', 'mcdev_scenarios']), JSON.stringify(names))

for (const t of registered) {
  check(`${t.name}: description 非空`, typeof t.description === 'string' && t.description.length > 40)
  check(`${t.name}: parameters 是 object schema`, t.parameters?.type === 'object' && !!t.parameters.properties)
  check(`${t.name}: execute 是函数`, typeof t.execute === 'function')
  check(`${t.name}: output.render 可用`, typeof t.output?.render === 'function' && typeof t.output.render(null, 'x')[0].text === 'string')
}

const devrun = registered.find((t) => t.name === 'mcdev_devrun')
const fam = devrun?.parameters?.properties?.family
check('family 是枚举且含 all', JSON.stringify(fam?.enum) === JSON.stringify(['fabric', 'forge', 'neoforge', 'all']), JSON.stringify(fam))

// 场景列表工具：真读一次 scenarios/，验证解析路径没写错（不依赖 DSH）。
const scenarios = registered.find((t) => t.name === 'mcdev_scenarios')
const listing = await scenarios.execute({}, { cwd: join(HERE, '..', '..') })
check('scenarios 工具能列出场景', /actions\.json/.test(listing) && /smoke\.json/.test(listing), listing.slice(0, 200))

console.log(failures === 0 ? '\nSELFTEST PASS' : `\nSELFTEST FAIL (${failures})`)
process.exit(failures === 0 ? 0 : 1)
