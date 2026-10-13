import assert from 'node:assert/strict'
import test from 'node:test'
import { evidenceAttachment, organizationEvidenceAttachment, summaryDocuments, summaryValue } from '../src/utils/businessSummary.ts'

const source = { origin: 'NOTICE_BODY', sectionTitle: '제출서류', excerpt: '모든 신청기업은 사업계획서를 제출한다.' }
const required = { title: '사업계획서', detail: '지정 서식 사용', requirementLevel: 'MANDATORY', stage: 'APPLICATION', source }
const analysis = items => ({ proposal: { preparation: { submissionDocuments: items } } })

test('missing values stay unknown while amount qualifiers and multiple deadlines remain intact', () => {
  for (const value of [null, undefined, '', '  ', '확인 필요', '원문에서 확인하지 못했습니다.']) assert.equal(summaryValue(value), null)
  for (const value of ['과제당 연간 최대 2억 원, 2년 이내 / 자부담 25% 이상', '총액 9.03억 원 내외 (연간 배분 확인 필요)', '온라인 10월 30일 18시\n원본 우편 11월 2일 도착분']) assert.equal(summaryValue(`  ${value}  `), value)
})

test('submission table preserves conditional and later-stage requirements and excludes ungrounded recommendations', () => {
  const conditional = { ...required, title: '협약 보증서', requirementLevel: 'CONDITIONAL', stage: 'AGREEMENT', appliesTo: '선정 기업만' }
  const excluded = [
    { ...required, requirementLevel: 'RECOMMENDED' },
    ...['COMPANY_INPUT', 'COMPANY_PROFILE', 'AI_RECOMMENDATION'].map(origin => ({ ...required, source: { ...source, origin } })),
    { ...required, source: { ...source, excerpt: '  ' } },
    { ...required, source: { origin: 'NOTICE_BODY' } },
    { ...required, source: undefined },
  ]
  assert.deepEqual(summaryDocuments(analysis([required, conditional, ...excluded])), [required, conditional])
  assert.deepEqual(summaryDocuments(null), [])
  assert.deepEqual(summaryDocuments({ proposal: {} }), [])
})

test('evidence links require one exact attachment name and a safe URL; filenames alone do not become required forms', () => {
  const item = { ...required, source: { ...source, origin: 'ATTACHMENT', attachmentName: '공고문.pdf' } }
  const file = { fileName: '공고문.pdf', downloadUrl: 'https://example.org/notice.pdf' }
  assert.equal(evidenceAttachment(item, [file]), file)
  assert.equal(evidenceAttachment(required, [file]), null)
  assert.equal(evidenceAttachment(item, [file, { ...file }]), null)
  assert.equal(evidenceAttachment(item, [{ ...file, fileName: '신청서.pdf' }]), null)
  for (const downloadUrl of ['javascript:alert(1)', 'data:text/html,hello', 'not a url']) assert.equal(evidenceAttachment(item, [{ ...file, downloadUrl }]), null)
})


test('organization evidence links use only their identified attachment and reject ambiguous or unsafe links', () => {
  const organization = { name: '한국진흥원', role: '시행기관', sourceType: 'ATTACHMENT', attachmentName: '공고문.pdf', excerpt: '시행기관: 한국진흥원' }
  const file = { fileName: '공고문.pdf', downloadUrl: 'https://example.org/notice.pdf' }
  assert.equal(organizationEvidenceAttachment(organization, [file]), file)
  assert.equal(organizationEvidenceAttachment({ ...organization, sourceType: 'NOTICE_BODY' }, [file]), null)
  assert.equal(organizationEvidenceAttachment(organization, [file, { ...file }]), null)
  assert.equal(organizationEvidenceAttachment(organization, [{ ...file, downloadUrl: 'javascript:alert(1)' }]), null)
})


test('independent submission guide renders without AI analysis and an empty guide does not revive old documents', () => {
  const guide = { documents: [required], notes: [] }
  assert.deepEqual(summaryDocuments(null, guide), [required])
  assert.deepEqual(summaryDocuments(analysis([{ ...required, title: '지난 서류' }]), guide), [required])
  assert.deepEqual(summaryDocuments(analysis([required]), { documents: [], notes: [] }), [])
  assert.deepEqual(summaryDocuments(analysis([required])), [required])
})
