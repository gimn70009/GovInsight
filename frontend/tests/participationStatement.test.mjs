import assert from 'node:assert/strict'
import test from 'node:test'
import { participationStatement } from '../src/utils/participationStatement.ts'

const entry = (status, text) => ({ displayStatus: status, rule: { text, scope: '원문 라벨', details: ['잘린 표 조각'], exceptions: [], guidance: [] }, context: '매출액·업력 혼합 원문', relation: 'LINKED', relatedCriteria: [] })

test('only verified LLM text is displayed, without recombining other criteria', () => {
  const text = '창업과제는 설립 7년 이하여야 합니다.'
  assert.equal(participationStatement(entry('VERIFIED', text)), text)
})

for (const status of ['EVIDENCE_ONLY', undefined, 'invalid']) {
  test(`unverified status ${status} cannot expose old or malformed sentences`, () => {
    assert.equal(participationStatement(entry(status, '제품이 대상입니다.')), '')
  })
}

test('a long verified sentence is preserved completely without ellipsis or clipping', () => {
  const text = '신청기업은 ' + '공고에 명시된 신청 요건과 예외를 '.repeat(40) + '확인해야 합니다.'
  assert.equal(participationStatement(entry('VERIFIED', text)), text)
})
