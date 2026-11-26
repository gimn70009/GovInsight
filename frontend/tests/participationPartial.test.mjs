import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { runInNewContext } from 'node:vm'
import test from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import ts from 'typescript'

// Render the actual component and its local dependencies; CSS has no server markup.
const modules = new Map()
function load(url) {
  if (modules.has(url.href)) return modules.get(url.href).exports
  const module = { exports: {} }
  modules.set(url.href, module)
  const compiled = ts.transpileModule(readFileSync(url, 'utf8'), {
    compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText
  const external = createRequire(url)
  const require = name => {
    if (name.endsWith('.css')) return {}
    if (!name.startsWith('.')) return external(name)
    const target = ['.tsx', '.ts', ''].map(ext => new URL(name + ext, url)).find(candidate => existsSync(candidate))
    assert.ok(target, name)
    return load(target)
  }
  runInNewContext(compiled, { require, module, exports: module.exports, URL })
  return module.exports
}
const { ParticipationConditions } = load(new URL('../src/components/ParticipationConditions.tsx', import.meta.url))
const text = '전담인력은 2인 이상 필수로 구성해야 합니다.'
const detail = status => ({ detectionId: 1, attachments: [], participationGuide: {
  rewriteStatus: status, categories: [{ code: 'COMPANY_REQUIREMENTS', rows: [{
    code: 'EXPERIENCE', entries: [{ displayStatus: 'VERIFIED', rule: {
      section: 'BASIC', text, scope: '성장사업', audience: '신청기관',
      details: [], guidance: [], exceptions: [], outcomes: [], sources: [],
    } }],
  }] }],
} })
const render = status => renderToStaticMarkup(createElement(ParticipationConditions, { detail: detail(status) }))

test('partial eligibility keeps valid conditions visible alongside the incomplete notice', () => {
  const html = render('PARTIAL')
  assert.ok(html.includes(text))
  assert.ok(html.includes('일부 신청 조건은 확인을 완료하지 못했습니다'))
  assert.ok(!html.includes('첨부 자격 확인'))
  assert.ok(!html.includes('신청 자격을 확인하고 있습니다'))
})

test('complete eligibility does not show the partial-result notice', () => {
  const html = render('READY')
  assert.ok(html.includes(text))
  assert.ok(!html.includes('일부 신청 조건은 확인을 완료하지 못했습니다'))
})
