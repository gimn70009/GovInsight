import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import test from 'node:test'
import { runInNewContext } from 'node:vm'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import ts from 'typescript'

const componentUrl = new URL('../src/components/BusinessTypeRow.tsx', import.meta.url)
const compiled = ts.transpileModule(readFileSync(componentUrl, 'utf8'), {
  compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.CommonJS },
}).outputText
const loaded = { exports: {} }
runInNewContext(compiled, { require: createRequire(componentUrl), module: loaded, exports: loaded.exports })
const render = (businessType, analysisPending = false) => renderToStaticMarkup(
  createElement(loaded.exports.BusinessTypeRow, { detail: { businessType, analysisPending } }),
)

test('notice nature distinguishes results, regulations, awards, and designation recruitment', () => {
  for (const label of ['일반 공고 · 선정 결과', '일반 공고 · 규정 고시', '포상·표창 모집', '지정 모집']) {
    const html = render({ status: 'NOT_APPLICABLE', noticeNature: { label } }, true)
    assert.ok(html.includes(label))
    assert.ok(!html.includes('확인 필요') && !html.includes('분석 중'))
  }
})

test('legacy unknown, pending, and classified support labels remain distinct', () => {
  assert.ok(render({ status: 'NOT_APPLICABLE' }).includes('해당 없음'))
  assert.ok(render(null).includes('확인 필요'))
  assert.ok(render(null, true).includes('분석 중'))
  assert.ok(render({ status: 'CLASSIFIED', code: 'DEMONSTRATION' }).includes('실증·시범'))
})
