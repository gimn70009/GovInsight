import assert from 'node:assert/strict'
import test from 'node:test'
import { companyCapabilities } from '../src/utils/companyCapabilities.ts'

const old = {
  confirmedFact: '제출서류로 참여기업의 최근 3개년 결산재무제표 제출을 요구합니다.',
  strategicInterpretation: '재무제표 준비 여부를 확인합니다.',
}
const company = {
  companyEvidenceId: 'service:1',
  confirmedFact: '회사는 제조 AI 에이전트 설계·개발 서비스를 제공합니다.',
  strategicInterpretation: '제조 AI 실증 과업의 설계에 활용합니다.',
}

test('legacy notice or company prose is not relabeled as grounded company capability', () => {
  assert.deepEqual(companyCapabilities([old, { ...old, confirmedFact: '회사는 제조 AI 기술을 보유합니다.' }]), [])
  assert.deepEqual(companyCapabilities([old, company]), [company])
  assert.equal(old.confirmedFact, '제출서류로 참여기업의 최근 3개년 결산재무제표 제출을 요구합니다.')
})

test('only company catalog references with complete content are displayed', () => {
  for (const companyEvidenceId of [null, '', 'NOTICE_BODY', 'unknownFields:1', 'service:0']) {
    assert.deepEqual(companyCapabilities([{ ...company, companyEvidenceId }]), [])
  }
  assert.deepEqual(companyCapabilities([{ ...company, confirmedFact: ' ' }]), [])
  assert.deepEqual(companyCapabilities([{ ...company, strategicInterpretation: '' }]), [])
  assert.deepEqual(companyCapabilities(undefined), [])
  assert.deepEqual(companyCapabilities(null), [])
  assert.deepEqual(companyCapabilities([]), [])
  assert.deepEqual(companyCapabilities([{ ...company, companyEvidenceId: 'case:7' }]),
    [{ ...company, companyEvidenceId: 'case:7' }])
})
