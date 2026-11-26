import assert from 'node:assert/strict'
import test from 'node:test'
import {
  companyParticipationRows, companyParticipationExclusions, companyParticipationGuidance, companyConditionScope,
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
    reviewRequired: false, notices: [],
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
  assert.equal(activeCompanyConditionProgram(selection, 1, programs.slice(0, 1), 8), '')
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

test('missing scope never becomes uncertainty because another role has business types', () => {
  const unscoped = entry('협회·단체는 비영리법인으로서 주무부처에 등록되어야 합니다.')
  const excluded = entry('참여제한 중인 기관은 신청이 불가합니다.', 'EXCLUSION')
  const subproject = entry('국내 소재·부품·장비 공급기업이 지원 대상입니다.', 'BASIC', { scope: '일반 유형 / DX 유형' })
  const data = { ...guide([row('BUSINESS_FORM', [unscoped]), row('SECTOR', [subproject]), row('CREDIT', [excluded])]),
    variants: [{ name: '일반 유형' }, { name: 'DX 유형' }] }
  const items = [...companyParticipationRows(data).flatMap(r => r.mandatory), ...companyParticipationExclusions(data)]
  assert.equal(items.length, 3)
  assert.equal(items.filter(value => companyConditionPresentation(value).scopeReviewRequired).length, 0)
  assert.equal(companyConditionPresentation(unscoped).scope, null)
  assert.equal(companyConditionPresentation(subproject).scope, '일반 유형 / DX 유형')
  assert.equal(companyConditionPresentation(entry('조건', 'BASIC', { scope: '공통' })).scope, '공통')
})

test('explicit review markers survive old caches without becoming business filters or hiding conditions', () => {
  for (const scope of ['확인 필요', '적용 범위 확인 필요', '일반형 / 적용 범위 확인 필요', '일반형 · 적용 범위 확인 필요']) {
    const value = entry('보유 요건을 확인해야 합니다.', 'BASIC', { scope })
    assert.equal(companyConditionPresentation(value).scopeReviewRequired, true)
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
  assert.equal(companyConditionAppliesTo(actual[0], '성장형'), true)
  assert.equal(companyConditionAppliesTo(actual[0], '일반형'), false)
  assert.deepEqual(companyParticipationExclusions(), [])
})

test('internal section metadata never becomes an applied business label', () => {
  for (const scope of ['상위 구역', '원문 구역', '신청자격 및 신청요건', '[상위 구역] 지원 대상', 'parent']) {
    const value = entry('기업이 신청할 수 있습니다.', 'BASIC', { scope })
    assert.equal(companyConditionScope(value), null)
    assert.equal(companyConditionPresentation(value).sentence, value.rule.text)
  }
})


test('numbered section headings cannot create a divided notice or false review badges', () => {
  const applicant = entry('협회·단체, 연구기관, 기업지원기관이 단독 또는 컨소시엄으로 신청할 수 있습니다.')
  for (const scope of ['아. 세부과제 지원유형', '2. 사업 유형', '① 지원대상', '(가) 신청자격',
    '3-1. 사업개요', '유형별 지원내용', '내역사업 구분', '신청자격 및 선정기준']) {
    const cached = entry('국내 소재·부품·장비 공급기업이 지원 대상입니다.', 'BASIC', { scope })
    assert.equal(companyConditionPresentation(applicant).scopeReviewRequired, false)
    assert.equal(companyConditionScope(cached), null)
    assert.equal(companyConditionPresentation(cached).sentence, cached.rule.text)
    assert.equal(cached.rule.scope, scope)
  }
})

test('real table types and hierarchy survive generic sections without losing uncertainty', () => {
  const scoped = entry('조건', 'BASIC', { scope: '일반 유형 / DX 유형' })
  assert.equal(companyConditionAppliesTo(scoped, 'DX 유형'), true)
  assert.equal(companyConditionAppliesTo(scoped, '성장형'), false)
  assert.equal(companyConditionScope(entry('조건', 'BASIC', { scope: '성장형 · 2. 신청자격 / 일반형' })), '성장형 / 일반형')
  const unknown = entry('조건', 'BASIC', { scope: '아. 세부과제 지원유형 / 적용 범위 확인 필요' })
  assert.equal(companyConditionPresentation(unknown).scopeReviewRequired, true)
  assert.equal(companyConditionScope(unknown), null)
  assert.equal(companyConditionAppliesTo(unknown, '성장형'), true)
})

test('scope and applicant category are separate model-owned values without a role dictionary', () => {
  for (const scope of ['총괄주관연구개발기관', '주관연구개발기관', '수요기업', '공급기업']) {
    const value = entry('비영리법인이 신청할 수 있습니다.', 'BASIC', { scope, audience: scope })
    assert.equal(companyConditionPresentation(value).scope, scope)
    assert.equal(companyConditionPresentation(value).scopeReviewRequired, false)
    assert.equal(companyConditionPresentation(value).audience, scope)
  }
  assert.equal(companyConditionScope(entry('조건', 'BASIC', { scope: '공급기업 역량진단' })), '공급기업 역량진단')
})


test('two real programs retain shared requirements and nested explanations when filtered', () => {
  const a = '1인 창조기업 지원센터', b = '중장년 기술창업센터'
  const shared = entry('전용공간을 갖추어야 합니다.', 'BASIC', { scope: `${a} / ${b}` })
  const manager = entry('총괄매니저는 자격을 갖추어야 합니다.', 'BASIC', {
    scope: '공통', details: ['별도의 상근 행정매니저를 두면 상근 겸직이 가능합니다.'],
  })
  for (const selected of [a, b]) {
    assert.equal(activeCompanyConditionProgram({ detectionId: 1, program: selected }, 1, [a, b], 17), selected)
    assert.equal(companyConditionAppliesTo(shared, selected), true)
    assert.equal(companyConditionAppliesTo(manager, selected), true)
    assert.equal(companyConditionScope(manager), '공통')
    assert.equal(companyConditionPresentation(manager).notes.length, 1)
  }
  assert.equal(companyConditionAppliesTo(entry('15석 이상입니다.', 'BASIC', { scope: a }), b), false)
  assert.equal(companyConditionScope(entry('사업 조건입니다.', 'BASIC', { scope: '매니저 양성 지원사업' })), '매니저 양성 지원사업')
})


test('legacy English common scopes normalize without rewriting applicant names or evidence', () => {
  for (const scope of ['common', ' COMMON ', '공통']) {
    const value = entry('신청 자격을 갖춘 기관이 신청할 수 있습니다.', 'BASIC', { scope, audience: 'Common Ground 기관' })
    assert.equal(companyConditionPresentation(value).scope, '공통')
    assert.equal(companyConditionPresentation(value).audience, 'Common Ground 기관')
    assert.equal(companyConditionAppliesTo(value, '성장사업'), true)
    assert.equal(value.rule.scope, scope)
  }
  const scoped = entry('신청 조건입니다.', 'BASIC', { scope: '성장사업 · common' })
  assert.equal(companyConditionScope(scoped), '성장사업 · 공통')
  assert.equal(companyConditionAppliesTo(scoped, '성장사업 · 일반형'), true)
  assert.equal(companyConditionAppliesTo(scoped, '다른사업 · 일반형'), false)
  assert.equal(companyConditionScope(entry('신청 조건입니다.', 'BASIC', { scope: 'Common Ground 지원사업' })), 'Common Ground 지원사업')
})


test('review-only conditions stay hidden while verified conditions and original evidence remain intact', () => {
  const sections = ['BASIC', 'ELIGIBILITY', 'TARGETED', 'PREFERENCE', 'EXCLUSION', 'APPLICATION_GUIDANCE']
  const verified = sections.map(section => entry('사업자등록이 된 기관이어야 합니다.', section))
  const review = sections.map(section => ({ ...entry('기관 등 단독 또는 컨소시엄', section), displayStatus: 'REVIEW_REQUIRED' }))
  const data = guide([row('BUSINESS_FORM', [...verified, ...review])])
  const original = structuredClone(data)
  const actual = companyParticipationRows(data)[4]
  assert.deepEqual(actual.mandatory, verified.slice(0, 3))
  assert.deepEqual(actual.preference, [verified[3]])
  assert.deepEqual(companyParticipationExclusions(data), [verified[4]])
  assert.deepEqual(companyParticipationGuidance(data), [verified[5]])
  assert.deepEqual(data, original)
})

test('administrative setup is separated from eligibility without losing its explanation', () => {
  const admin = entry('담당자는 전산등록을 완료해야 합니다.', 'APPLICATION_GUIDANCE', {
    guidance: ['일부 세부 조건은 원문 근거를 확인해 주세요.'],
  })
  const required = entry('사업자등록이 된 기관이어야 합니다.')
  const data = guide([row('BUSINESS_FORM', [required, admin])])
  assert.deepEqual(companyParticipationRows(data)[4].mandatory, [required])
  assert.deepEqual(companyParticipationGuidance(data), [admin])
  assert.equal(companyConditionPresentation(admin).notices.length, 1)
})
