import assert from 'node:assert/strict'
import test from 'node:test'
import { isApplicationExpired, parseApplicationDeadline, normalizeLegalNarrative } from '../src/utils/noticePresentation.ts'

const analysis = (deadline, reason = '', summary = '') => ({
  applicationDeadline: deadline, summary, proposal: { preparation: null },
  opportunity: { dimensions: [{ type: 'URGENCY', reason }] },
})
const now = Date.parse('2026-09-11T12:00:00+09:00')

test('open deadline overrides misleading summary and stale urgency closure text', () => {
  assert.equal(isApplicationExpired(analysis('2026-10-01 18:00', '마감 지남', '접수기간 종료 전까지 제출하며 이후 신청은 불가능합니다.'), now), false)
  assert.equal(isApplicationExpired(analysis(null, '신청 마감일은 2026년 10월 1일이며 분석일 기준 남은 20일입니다.', '접수기간 종료 전 준비합니다.'), now), false)
  assert.equal(isApplicationExpired(analysis(null, '', '접수기간 종료 상태입니다.'), now), false)
  assert.equal(isApplicationExpired(analysis('2026-09-10', '남은 20일입니다.'), now), true)
})

test('deadline time and date-only cutoff use Korea time including exact boundary', () => {
  const cutoff = Date.parse('2026-10-01T18:00:00+09:00')
  for (const value of ['2026-10-01 18:00', '2026년 10월 1일 18시 00분', '2026.10.01.(목) 18:00']) {
    assert.equal(parseApplicationDeadline(value), cutoff, value)
    assert.equal(isApplicationExpired(analysis(value), cutoff - 1), false)
    assert.equal(isApplicationExpired(analysis(value), cutoff), true)
  }
  const midnight = Date.parse('2026-10-02T00:00:00+09:00')
  assert.equal(parseApplicationDeadline('2026-10-01'), midnight)
  assert.equal(isApplicationExpired(analysis('2026-10-01'), midnight - 1), false)
  assert.equal(isApplicationExpired(analysis('2026-10-01'), midnight), true)
})

test('ranges use end date; unknown or ambiguous dates never force closure', () => {
  assert.equal(parseApplicationDeadline('2026-09-01 ~ 2026-10-01 18:00'), Date.parse('2026-10-01T18:00:00+09:00'))
  for (const value of ['', '상시 접수', '2026-02-30', '2026-09-01 ~ 10-01', '2026-09-01 또는 2026-10-01', '2026-10-01 25:00']) {
    assert.equal(parseApplicationDeadline(value), null, value)
    assert.equal(isApplicationExpired(analysis(value), now), false)
  }
})

test('stored legal prose preserves conditions and exceptions while normalizing endings', () => {
  const original = '성과는 각 기관 소유이다. 다만 별도 협의로 달리 정할 수 있다. 사전 확인이 필요하다. 공동연구 협약 존재 여부 확인'
  const expected = '성과는 각 기관 소유입니다. 다만 별도 협의로 달리 정할 수 있습니다. 사전 확인이 필요합니다. 공동연구 협약 존재 여부를 확인할 필요가 있습니다.'
  assert.equal(normalizeLegalNarrative(original), expected)
  assert.equal(normalizeLegalNarrative(expected), expected)
  assert.equal(normalizeLegalNarrative('지원 비율은 75.5%이다. 의무는 아니다.'), '지원 비율은 75.5%입니다. 의무는 아닙니다.')
})
