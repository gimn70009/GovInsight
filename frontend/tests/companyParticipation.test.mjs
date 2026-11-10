import assert from 'node:assert/strict'
import test from 'node:test'
import {
  companyParticipationRows, companyParticipationExclusions, companyConditionScope, companyHasBusinessDivision,
  companyConditionPresentation, companyConditionAppliesTo, activeCompanyConditionProgram,
} from '../src/utils/companyParticipation.ts'

const entry = (text, section = 'BASIC', extra = {}) => ({
  displayStatus: 'VERIFIED', context: null, relation: null, relatedCriteria: [],
  rule: { section, label: '조건', text, scope: null, audience: null, details: [], exceptions: [], guidance: [], outcomes: [],
    sources: [{ origin: 'NOTICE_BODY', excerpt: text }], ...extra },
})
const guide = rows => ({ rewriteStatus: 'READY', categories: [{ code: 'COMPANY_REQUIREMENTS', label: '참여기업 조건', rows }], conditions: [] })
const row = (code, entries) => ({ code, label: '서버 라벨', entries, status: 'STATED' })
const applicantRows = entries => companyParticipationRows(guide([row('BUSINESS_FORM', entries)]))[4]

test('seven supported fields retain the server classification and no unrelated fields are invented', () => {
  const actual = companyParticipationRows(guide([row('REGION', [entry('서울 소재')]), row('BUSINESS_AGE', [entry('업력 7년 이내')])]))
  assert.deepEqual(actual.map(value => [value.code, value.label]), [['BUSINESS_AGE', '업력'], ['REVENUE', '매출·수출'], ['IP', '특허·인증·지정'], ['SECTOR', '업종·품목·서비스'], ['BUSINESS_FORM', '신청 주체'], ['EXPERIENCE', '실적·수행 역량'], ['CREDIT', '신용·재무 요건']])
  assert.equal(actual.flatMap(value => value.mandatory).length, 1)
  assert.equal(companyParticipationRows().length, 7)
})

test('mandatory, preference and exclusion remain distinct without changing supplied wording', () => {
  const required = entry('접수일 기준 업력 7년 이내 (단, 신산업 분야는 10년 이내)')
  const preferred = entry('업력 3년 이내 초기기업 가점 2점', 'PREFERENCE')
  const excluded = entry('휴·폐업 중인 기업은 신청할 수 없습니다.', 'EXCLUSION')
  const actual = applicantRows([required, preferred, excluded])
  assert.deepEqual(actual.mandatory, [required])
  assert.deepEqual(companyParticipationExclusions(guide([row('BUSINESS_FORM', [required, preferred, excluded])])), [excluded])
  assert.deepEqual(actual.preference, [preferred])
})

test('verified prose is not rechecked against keywords, endings, field-specific patterns or quote text', () => {
  for (const [code, text] of [
    ['SECTOR', '신청서 및 제출서류에 사업자등록 업종이 명시되어야 합니다.'],
    ['CREDIT', '기업 유형에 따른 재무요건을 충족해야 합니다.'],
    ['BUSINESS_AGE', '공고일 기준 업력 7년 이내'],
    ['BUSINESS_FORM', '소재지와 상시근로자 수에 관한 조건을 모두 충족해야 합니다.'],
    ['EXPERIENCE', '창업기업 지원역량을 보유한 기관이어야 합니다.'],
  ]) {
    const value = entry(text, 'BASIC', { sources: [{ origin: 'ATTACHMENT', sectionTitle: '제출서류', excerpt: '서버가 검증한 원문 인용' }] })
    value.supportingQuotes = ['서버가 복원한 별도 인용']
    const result = companyParticipationRows(guide([row(code, [value])])).find(item => item.code === code)
    assert.deepEqual(result.mandatory, [value], code)
    assert.equal(companyConditionPresentation(value).sentence, text)
  }
})

test('nonempty VERIFIED text is displayed without adding a second source validation gate', () => {
  const value = entry('서버가 제공한 조건', 'BASIC', { sources: [] })
  assert.deepEqual(applicantRows([value]).mandatory, [value])
})

test('unverified, review and blank conditions stay out of the condition table', () => {
  const unverified = { ...entry('특허 보유 필수'), displayStatus: 'EVIDENCE_ONLY' }
  const review = entry('적용 기준 확인 필요', 'REVIEW')
  const blank = entry('  ', 'BASIC', { sources: [{ origin: 'NOTICE_BODY', excerpt: '신청 대상 원문' }] })
  const data = guide([row('IP', [unverified, review, blank, entry('감점', 'DEDUCTION'), entry('사업비', 'FUNDING')])])
  const actual = companyParticipationRows(data)[2]
  assert.deepEqual(actual.mandatory, [])
  assert.deepEqual(actual.preference, [])
})

test('an empty or unavailable paid cache does not generate a condition from original evidence', () => {
  for (const status of ['READY', 'UNAVAILABLE', 'PENDING']) {
    const value = { ...entry('', 'EVIDENCE', { sources: [{ origin: 'NOTICE_BODY', excerpt: '[표] 신청 조건 원문' }] }), displayStatus: 'EVIDENCE_ONLY' }
    const data = { ...guide([row('BUSINESS_FORM', [value])]), rewriteStatus: status }
    assert.equal(companyParticipationRows(data)[4].mandatory.length, 0)
  }
})

test('server text, scope, audience and explicit notes are displayed verbatim', () => {
  const text = '① 신청자격: 중소기업기본법 제2조에 따른 기업\n※ 원문에 포함된 추가 문장'
  const value = entry(text, 'BASIC', {
    scope: '① 제조AI · 자율형공장 / AX선도모델', audience: '도입기업',
    details: ['* 최근 3개년 매출액 1회 이상 달성', '  별도의 공백도 유지  '],
    exceptions: ['단, 추천기업은 예외', '* 최근 3개년 매출액 1회 이상 달성'],
  })
  assert.deepEqual(companyConditionPresentation(value), {
    scope: value.rule.scope, scopeReviewRequired: false, audience: '도입기업', sentence: text,
    notes: ['* 최근 3개년 매출액 1회 이상 달성', '  별도의 공백도 유지  ', '단, 추천기업은 예외'],
  })
  assert.equal(value.rule.text, text)
})

test('scope is not inferred from source headings and only surrounding whitespace is normalized', () => {
  for (const scope of ['① 점프업100', '는사업', '  실제 유형  ']) {
    const value = entry('국내 중소기업이 지원 대상입니다.', 'BASIC', { scope })
    assert.equal(companyConditionScope(value), scope.trim())
    assert.equal(companyConditionPresentation(value).sentence, value.rule.text)
  }
  for (const scope of [null, '', '   ']) {
    const value = entry('조건', 'BASIC', { scope, audience: '  ', sources: [{ origin: 'NOTICE_BODY', sectionTitle: '유형: 추측하면 안 되는 유형', excerpt: '조건' }] })
    assert.equal(companyConditionScope(value), null)
    assert.equal(companyConditionPresentation(value).audience, null)
  }
})

test('long conditions are not shortened and source glyphs are not rewritten in the browser', () => {
  const text = '도입기업은 ' + '특정 문서와 신청 자격을 모두 충족하고 '.repeat(30) + '등록 상태가 ●인 기업이어야 합니다.'
  const value = entry(text, 'BASIC', { scope: '공통', audience: '도입기업' })
  assert.equal(companyConditionPresentation(value).sentence, text)
  assert.equal(applicantRows([value]).mandatory[0].rule.text, text)
})

test('exactly equal display contracts merge citations from different file formats without altering conditions', () => {
  const text = '창업기업 지원역량을 보유한 기관이 지원 대상입니다.'
  const first = entry(text, 'BASIC', { scope: '공통', details: ['역량 보유를 확인합니다.'] })
  first.supportingQuotes = ['창업기업 지원역량 보유 기관']
  const second = structuredClone(first)
  second.rule.sources = [{ origin: 'ATTACHMENT', attachmentName: '공고.pdf', excerpt: '창업기업 지원역량 보유 기관' }]
  second.supportingQuotes = ['다른 형식의 원문']
  const actual = applicantRows([first, second, structuredClone(first)])
  assert.equal(actual.mandatory.length, 1)
  assert.equal(actual.mandatory[0].rule.text, text)
  assert.deepEqual(actual.mandatory[0].rule.sources, [...first.rule.sources, ...second.rule.sources])
  assert.deepEqual(actual.mandatory[0].supportingQuotes, [...first.supportingQuotes, ...second.supportingQuotes])
  assert.equal(first.rule.sources.length, 1)
})

test('different text, context, roles, kinds and exceptions never merge even when the quoted source is the same', () => {
  const first = entry('국내 중소기업이 지원 대상입니다.', 'BASIC', { scope: '공통' })
  for (const change of [
    { text: '국내  중소기업이 지원 대상입니다.' }, { text: '국내 중소기업이 신청 대상입니다.' },
    { text: '국내 중소기업 중 지원역량을 갖춘 기업이 지원 대상입니다.' },
    { scope: null }, { scope: '별도 유형' }, { audience: '도입기업' }, { section: 'PREFERENCE' },
    { section: 'ELIGIBILITY' }, { details: ['매출 10억원 이상'] }, { exceptions: ['추천기업 예외'] },
    { guidance: ['신청일 기준'] }, { outcomes: [{ variant: 'A', status: 'ELIGIBLE' }] },
  ]) {
    const second = { ...structuredClone(first), rule: { ...structuredClone(first.rule), ...change } }
    const actual = applicantRows([first, second])
    assert.equal(actual.mandatory.length + actual.preference.length, 2, JSON.stringify(change))
  }
  for (const change of [{ context: '다른 문맥' }, { relation: 'ALTERNATIVE' }, { relatedCriteria: ['REVENUE'] }]) {
    assert.equal(applicantRows([first, { ...structuredClone(first), ...change }]).mandatory.length, 2)
  }
})

test('same statement with different or partially matching scopes remains separate without hierarchy guessing', () => {
  const text = '국내 중소기업이 지원 대상입니다.'
  const items = [null, '공통', '자율형공장', '제조AI · 자율형공장'].map(scope => entry(text, 'BASIC', { scope }))
  assert.equal(applicantRows(items).mandatory.length, 4)
})

test('explicit program filter retains common and unspecified conditions and exact alternative programs', () => {
  const value = scope => entry('국내 중소기업이 지원 대상입니다.', 'BASIC', { scope })
  const selected = '스마트공장 · 자율형공장'
  for (const scope of [null, '공통', '스마트공장 · 공통', selected, selected + ' / 일반형']) {
    assert.equal(companyConditionAppliesTo(value(scope), selected), true, scope)
  }
  for (const scope of ['스마트공장 · AI형', '다른사업 · 공통', '자율형공장']) {
    assert.equal(companyConditionAppliesTo(value(scope), selected), false, scope)
    assert.equal(companyConditionAppliesTo(value(scope), ''), true, scope)
  }
})

test('a hidden or stale program filter never hides conditions after a notice or result change', () => {
  const selection = { detectionId: 1, program: '일반형' }
  const programs = ['일반형', '성장형', '도약형']
  assert.equal(activeCompanyConditionProgram(selection, 1, programs, 8), '일반형')
  assert.equal(activeCompanyConditionProgram(selection, 2, programs, 8), '')
  assert.equal(activeCompanyConditionProgram(selection, 1, programs, 7), '')
  assert.equal(activeCompanyConditionProgram(selection, 1, programs.slice(0, 2), 8), '')
  assert.equal(activeCompanyConditionProgram(selection, 1, ['특화형', '성장형', '도약형'], 8), '')
})

test('cached prompt descriptions do not become business filters or applicant labels', () => {
  const text = '창업도시 조성 프로젝트를 추진하는 광역자치단체(시·도)를 모집합니다.'
  for (const scope of ["실제 사업·과제유형(명시공통='공통')", '실제 사업·과제유형', 'scope', '유형 미지정', '적용 범위 확인 필요']) {
    const value = entry(text, 'BASIC', { scope, audience: '신청역할' })
    assert.equal(companyConditionScope(value), null)
    assert.equal(companyConditionPresentation(value).audience, null)
    assert.equal(companyConditionPresentation(value).sentence, text)
    assert.equal(companyConditionAppliesTo(value, '초기창업패키지'), true)
    assert.deepEqual(applicantRows([value]).mandatory, [value])
    assert.equal(value.rule.scope, scope)
  }
  const known = entry(text, 'BASIC', { scope: '  초기창업패키지 · 일반형  ', audience: ' 광역자치단체 ' })
  assert.equal(companyConditionPresentation(known).scope, '초기창업패키지 · 일반형')
  assert.equal(companyConditionPresentation(known).audience, '광역자치단체')
  assert.equal(companyConditionScope(entry(text, 'BASIC', { scope: '공통' })), '공통')
})

test('absence of a business division differs from an unknown scope within a divided notice', () => {
  const unscoped = entry('광역자치단체가 신청할 수 있습니다.')
  const single = guide([row('BUSINESS_FORM', [unscoped])])
  assert.equal(companyHasBusinessDivision(single), false)
  assert.equal(companyConditionPresentation(unscoped, false).scopeReviewRequired, false)
  for (const explicit of [
    guide([row('BUSINESS_FORM', [unscoped, entry('조건', 'BASIC', { scope: '일반형 / 성장형' })])]),
    { ...single, variants: [{ name: '유형1', description: '일반형' }, { name: '유형2', description: '성장형' }] },
  ]) {
    assert.equal(companyHasBusinessDivision(explicit), true)
    assert.equal(companyConditionPresentation(unscoped, true).scopeReviewRequired, true)
  }
  const common = entry('조건', 'BASIC', { scope: '공통' })
  assert.equal(companyHasBusinessDivision(guide([row('BUSINESS_FORM', [common])])), false)
  assert.equal(companyConditionPresentation(common, true).scope, '공통')
  assert.equal(companyConditionPresentation(common, true).scopeReviewRequired, false)
  assert.equal(companyHasBusinessDivision(guide([row('BUSINESS_FORM', [
    entry('조건', 'BASIC', { scope: "실제 사업·과제유형(명시공통='공통')" }),
  ])])), false)
})

test('explicit review markers survive old caches without becoming business filters or hiding conditions', () => {
  for (const scope of ['확인 필요', '적용 범위 확인 필요', '일반형 / 적용 범위 확인 필요']) {
    const value = entry('보유 요건을 확인해야 합니다.', 'BASIC', { scope })
    assert.equal(companyConditionPresentation(value, false).scopeReviewRequired, true)
    assert.equal(companyConditionScope(value), scope.startsWith('일반형') ? '일반형' : null)
    assert.equal(companyConditionAppliesTo(value, '성장형'), true)
    assert.equal(value.rule.scope, scope)
  }
})

test('exclusions remain separate, deduplicate across categories and retain business filters and evidence', () => {
  const excluded = entry('휴업 기업은 지원에서 제외됩니다.', 'EXCLUSION', { scope: '성장형' })
  const second = { ...excluded, rule: { ...excluded.rule, sources: [{ origin: 'ATTACHMENT', excerpt: '추가 근거' }] } }
  const unverified = { ...entry('검증되지 않은 제외 조건', 'EXCLUSION'), displayStatus: 'EVIDENCE_ONLY' }
  const data = guide([row('CREDIT', [excluded, unverified]), row('EXPERIENCE', [second])])
  assert.equal(companyParticipationRows(data).flatMap(r => [...r.mandatory, ...r.preference]).length, 0)
  const actual = companyParticipationExclusions(data)
  assert.equal(actual.length, 1)
  assert.equal(actual[0].rule.sources.length, 2)
  assert.equal(companyHasBusinessDivision(data), true)
  assert.equal(companyConditionAppliesTo(actual[0], '성장형'), true)
  assert.equal(companyConditionAppliesTo(actual[0], '일반형'), false)
  assert.deepEqual(companyParticipationExclusions(), [])
})

test('internal section metadata never becomes an applied business label', () => {
  for (const scope of ['상위 구역', '원문 구역', '신청자격 및 신청요건', '[상위 구역] 지원 대상', 'parent']) {
    const value = entry('기업이 신청할 수 있습니다.', 'BASIC', { scope })
    assert.equal(companyConditionScope(value), null)
    assert.equal(companyHasBusinessDivision(guide([row('BUSINESS_FORM', [value])])), false)
    assert.equal(companyConditionPresentation(value).sentence, value.rule.text)
  }
})
