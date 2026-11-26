import assert from 'node:assert/strict'
import test from 'node:test'
import { companyConditionPresentation } from '../src/utils/companyParticipation.ts'

const entry = (sources, displayStatus) => ({ displayStatus, rule: { section: 'EVIDENCE', text: '', sources } })

test('review text does not present an inaccurate cached condition as fact', () => {
  const value = entry([], 'REVIEW_REQUIRED')
  value.rule.text = '지원에서 제외되고 협약해약 및 제재가 가능합니다.'
  value.rule.guidance = ['제외 사유와 제재 조치의 연결을 확인해야 합니다.']
  const presentation = companyConditionPresentation(value)
  assert.equal(presentation.sentence, '신청 요건의 정확한 내용은 원문 근거를 확인해 주세요.')
  assert.deepEqual(presentation.notices, value.rule.guidance)
  assert.equal(value.rule.text, '지원에서 제외되고 협약해약 및 제재가 가능합니다.')
})
