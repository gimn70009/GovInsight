import assert from 'node:assert/strict'
import test from 'node:test'
import { documentPresentation, evidenceAttachment, formatSupportScale, organizationEvidenceAttachment, summaryDocuments, summaryValue } from '../src/utils/businessSummary.ts'

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


test('support amounts use readable Korean won units without changing business conditions', () => {
  const text = '유형1은 기업당 최대 42백만원(보조율 70%)로 H/W·S/W 및 검증을 지원하고 유형2는 검증비용으로 기업당 최대 3.5백만원을 지원합니다.'
  assert.equal(formatSupportScale(text), '유형1은 기업당 최대 4,200만 원(보조율 70%)로 H/W·S/W 및 검증을 지원하고 유형2는 검증비용으로 기업당 최대 350만 원을 지원합니다.')
  assert.equal(formatSupportScale('총 903백만 원 내외, 연간 최대 42백만원\n자부담 25% 이상 / 2년 이내'), '총 9억 300만 원 내외, 연간 최대 4,200만 원\n자부담 25% 이상 / 2년 이내')
  assert.equal(formatSupportScale('42백만원(70%), 3.5백만원 / 월'), '4,200만 원(70%), 350만 원 / 월')
})

test('currency conversion is exact for decimal amounts, comma groups and large numbers', () => {
  const cases = [
    ['42,000,000원', '4,200만 원'], ['3,500천원', '350만 원'],
    ['0.5억원', '5,000만 원'], ['9.03억 원', '9억 300만 원'],
    ['1.2345억원', '1억 2,345만 원'], ['0.000001백만원', '1원'],
    ['42.001백만원', '4,200만 1,000원'], ['3.5천원', '3,500원'],
    ['0백만원', '0원'], ['-3.5백만원', '-350만 원'], ['+42백만원', '+4,200만 원'],
    ['1,234,567백만원', '1조 2,345억 6,700만 원'],
    ['9,007,199,254,740,993원', '9,007조 1,992억 5,474만 993원'],
  ]
  for (const [input, output] of cases) {
    assert.equal(formatSupportScale(input), output, input)
    assert.equal(formatSupportScale(output), output, `idempotent: ${output}`)
  }
})

test('both ends of explicit and shared-unit ranges keep their values', () => {
  for (const separator of ['~', ' ~ ', '∼', '–', '-', '부터 ']) {
    assert.equal(formatSupportScale(`3.5${separator}42백만원`), `350만 원${separator}4,200만 원`)
  }
  assert.equal(formatSupportScale('3.5백만원~42백만원 이하'), '350만 원~4,200만 원 이하')
  assert.equal(formatSupportScale('42백만원, 50백만원'), '4,200만 원, 5,000만 원')
  assert.equal(formatSupportScale('42백만원/50백만원'), '4,200만 원/5,000만 원')
})

test('uncertain money expressions, compound amounts and non-won values remain unchanged', () => {
  for (const value of [
    '1억 5천만원', '1억5,000만원', '3조 2억 5천만원',
    '42·50백만원', '42, 50백만원', '10~20~30백만원',
    '1,23백만원', '1.2.3백만원', '1e3백만원', '1e-3백만원',
    '0.0000001백만원', '0.5원', '0.0015~3백원',
    '1~1,23백만원', '42백만 달러', 'USD 42 million',
    '지원액 42 (단위: 백만원)', '보조율 70%, 3.5개월, 2026-10-19', '확인 필요',
  ]) assert.equal(formatSupportScale(value), value, value)
})


test('document presentation separates verified names from formats and conditions without changing evidence', () => {
  const item = { ...required, documentNames: ['사업자등록증', '법인등기부등본'], formats: ['PDF'],
    conditions: ['법인기업은 두 서류를 모두 제출합니다.'], detail: '각 1부를 준비해 주세요.' }
  assert.deepEqual(documentPresentation(item), {
    names: ['사업자등록증', '법인등기부등본'], formats: ['PDF'],
    conditions: ['법인기업은 두 서류를 모두 제출합니다.'], description: '각 1부를 준비해 주세요.',
  })
  assert.equal(item.source.excerpt, source.excerpt)
})

test('legacy guidance is kept in a disclosure and missing conditional criteria are not invented', () => {
  const legacy = { ...required, detail: '증빙자료\n사업계획 내 기재사항 증빙 등\n발표자료\nPDF', requirementLevel: 'CONDITIONAL' }
  assert.deepEqual(documentPresentation(legacy).names, ['사업계획서'])
  assert.equal(documentPresentation(legacy).description, null)
  assert.deepEqual(documentPresentation(legacy).conditions, [legacy.detail])
  const unknown = { ...required, documentNames: ['확인서'], detail: null, requirementLevel: 'CONDITIONAL' }
  assert.match(documentPresentation(unknown).conditions[0], /구체적인 적용 대상은 원문을 확인/u)
})
