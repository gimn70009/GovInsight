import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'

const compiled = ts.transpileModule(readFileSync(new URL('../src/utils/analysisStatus.ts', import.meta.url), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS },
}).outputText
const loaded = { exports: {} }
runInNewContext(compiled, { module: loaded, exports: loaded.exports })
const { analysisStatusPresentation, missingAnalysisNotice } = loaded.exports

test('only actual pending work is labelled as analyzing, including legacy missing scores', () => {
  assert.equal(analysisStatusPresentation('FAILED').label, '분석 실패')
  assert.equal(analysisStatusPresentation('FAILED').tone, 'danger')
  assert.equal(analysisStatusPresentation('PENDING').label, '분석 중')
  assert.equal(analysisStatusPresentation('COMPLETED').label, '분석 완료')
  assert.equal(analysisStatusPresentation(undefined).label, '확인 필요')
})

test('failed detail uses the safe API reason and overrides proposal-level pending', () => {
  const reason = '본문·첨부파일에서 읽을 텍스트를 찾지 못했어요.'
  const notice = missingAnalysisNotice({ analysisStatus: 'FAILED', analysisPending: true, analysisFailureReason: reason })
  assert.equal(notice.title, '분석 실패')
  assert.equal(notice.pending, false)
  assert.equal(notice.description, reason)
})

test('legacy terminal detail fails clearly while pending detail continues refreshing', () => {
  assert.match(missingAnalysisNotice({ analysisPending: false }).description, /다시 모니터링/)
  assert.equal(missingAnalysisNotice({ analysisPending: false }).title, '분석 실패')
  assert.equal(missingAnalysisNotice({ analysisStatus: 'PENDING', analysisPending: true }).title, 'AI 분석을 준비하고 있어요')
})
