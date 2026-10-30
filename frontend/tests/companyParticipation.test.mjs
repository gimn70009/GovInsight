import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { companyParticipationRows, companyConditionEmptyText, companyConditionScope, formatCompanyCondition, isSpecificIndustryCondition, companyConditionSentence, scopedCompanyConditionSentence, companyConditionPresentation, isReadableCompanyCondition, companyConditionAppliesTo, activeCompanyConditionProgram } from '../src/utils/companyParticipation.ts'

const entry = (text, section = 'BASIC', extra = {}) => ({
  displayStatus: 'VERIFIED', context: null, relation: null, relatedCriteria: [],
  rule: { section, label: '조건', text, scope: null, audience: null, details: [], exceptions: [], guidance: [], outcomes: [],
    sources: [{ origin: 'NOTICE_BODY', excerpt: text }], ...extra },
})
const guide = rows => ({ rewriteStatus: 'READY', categories: [{ code: 'COMPANY_REQUIREMENTS', label: '참여기업 조건', rows }], conditions: [] })
const row = (code, entries, status = 'STATED') => ({ code, label: '서버 라벨', entries, status })

test('seven fields are supported and old unrelated category rows never return', () => {
  const actual = companyParticipationRows(guide([row('REGION', [entry('서울 소재')]), row('BUSINESS_AGE', [entry('업력 7년 이내')])]))
  assert.deepEqual(actual.map(value => [value.code, value.label]), [['BUSINESS_AGE', '업력'], ['REVENUE', '매출·수출'], ['IP', '특허·인증·지정'], ['SECTOR', '업종·품목·서비스'], ['BUSINESS_FORM', '신청 주체'], ['EXPERIENCE', '실적·수행 역량'], ['CREDIT', '신용·재무 요건']])
  assert.equal(actual.flatMap(value => value.mandatory).length, 1)
  assert.equal(companyParticipationRows().length, 7)
})

test('mandatory and preference originals remain separate with their qualifiers', () => {
  const required = entry('접수일 기준 업력 7년 이내 (단, 신산업 분야는 10년 이내)')
  const preferred = entry('업력 3년 이내 초기기업 가점 2점', 'PREFERENCE')
  const actual = companyParticipationRows(guide([row('BUSINESS_AGE', [required, preferred])]))[0]
  assert.deepEqual(actual.mandatory, [required])
  assert.deepEqual(actual.preference, [preferred])
  assert.deepEqual(actual.evidence, [])
})

test('unstructured raw fragments stay in evidence instead of being wrapped as sentences', () => {
  const item = entry('직전년도 매출액 100억 원 이상\n수출액 제외', 'ELIGIBILITY', {
    sources: [{ origin: 'ATTACHMENT', excerpt: '직전년도 매출액 100억 원 이상 수출액 제외' }],
  })
  const actual = companyParticipationRows(guide([row('REVENUE', [item])]))[1]
  assert.equal(actual.mandatory.length, 0)
  assert.equal(actual.evidence[0].rule.sources[0].excerpt, item.rule.sources[0].excerpt)
  assert.equal(companyConditionSentence(item.rule.text, false), '')
})

test('unverified or source-mismatched text never becomes a mandatory condition', () => {
  const first = { ...entry('특허 보유 필수'), displayStatus: 'EVIDENCE_ONLY' }
  const second = entry('특허 2건 보유 필수', 'BASIC', { sources: [{ origin: 'NOTICE_BODY', excerpt: '특허 보유 시 가점' }] })
  const actual = companyParticipationRows(guide([row('IP', [first, second])]))[2]
  assert.deepEqual(actual.mandatory, [])
  assert.deepEqual(actual.preference, [])
  assert.equal(actual.evidence.length, 1)
  assert.equal(actual.evidence[0].rule.text, '')
  assert.equal(actual.evidence[0].rule.sources.length, 2)
})

test('unclear requirements are not assigned to a column; funding and deductions are excluded', () => {
  const actual = companyParticipationRows(guide([row('SECTOR', [entry('제조업 관련 조건', 'REVIEW'), entry('제조업 감점', 'DEDUCTION'), entry('제조업 지원금', 'FUNDING')])]))[3]
  assert.deepEqual(actual.mandatory, [])
  assert.deepEqual(actual.preference, [])
  assert.equal(actual.evidence[0].rule.sources.length, 1)
})

test('source-free verified conditions remain unresolved', () => {
  const actual = companyParticipationRows(guide([row('IP', [entry('등록특허 보유', 'BASIC', { sources: [] })])]))[2]
  assert.deepEqual(actual.mandatory, [])
  assert.equal(actual.evidence.length, 1)
})

test('only supplied absence and workflow states are described without asserting no eligibility requirements', () => {
  assert.equal(companyConditionEmptyText('READY', false), '확인된 조건 없음')
  assert.equal(companyConditionEmptyText('READY', true), '확인 필요')
  assert.equal(companyConditionEmptyText('PENDING', false), '조건 확인 중')
  assert.equal(companyConditionEmptyText('UNAVAILABLE', false), '확인 필요')
  assert.equal(companyConditionEmptyText(undefined, false), '확인 필요')
})

test('explicit track scope stays separate from the original threshold with no invented shared requirement', () => {
  const scoped = entry('직전년도 매출액 100억 원 이상', 'BASIC', { scope: '스케일업 100억 트랙' })
  assert.equal(companyConditionScope(scoped), '스케일업 100억 트랙')
  assert.equal(companyParticipationRows(guide([row('REVENUE', [scoped])]))[1].mandatory[0].rule.text, '직전년도 매출액 100억 원 이상')
  assert.equal(companyConditionScope(entry('스케일업 100억 트랙: 매출액 100억 원 이상', 'BASIC', { scope: '스케일업100억트랙' })), '스케일업100억트랙')
  assert.equal(companyConditionScope(entry('매출액 100억 원 이상', 'BASIC')), null)
})


test('requested eligibility sentence reads naturally without deleting its legal basis', () => {
  const raw = '**□ (신청자격) ｢중소기업기본법｣ 제2조에 따라 공고일 기준 업력 7년 이상의 중소기업 중 세부 프로그램별 신청요건을 충족하는 기업**'
  assert.deepEqual(formatCompanyCondition(raw), {
    lead: '신청 자격은 중소기업기본법 제 2조에 따라 공고일 기준 업력 7년 이상의 중소기업 중 세부 프로그램별 신청요건을 충족하는 기업',
    notes: [],
  })
  assert.equal(entry(raw).rule.text, raw)
})

test('long criteria split only explicit footnotes and keep all exceptions and years', () => {
  const raw = '□ (지원대상) 업력 7년 이상의 매출액 100억원 달성* 중소기업\n* 최근 3개년(’23~’25년) 표준재무제표증명원상 매출액 100억원 1회 이상 달성 기업\n** 마일스톤트랙 : 매출 100억원을 달성한 창업 3년 미만인 창업지원 사업 참여기업을 대상으로,\n창업지원 사업 운영기관의 추천을 통해 별도 선발(10개사 내)'
  assert.deepEqual(formatCompanyCondition(raw), {
    lead: '지원 대상은 업력 7년 이상의 매출액 100억원 달성 중소기업',
    notes: [
      '최근 3개년(’23~’25년) 표준재무제표증명원상 매출액 100억원 1회 이상 달성 기업',
      '마일스톤트랙 : 매출 100억원을 달성한 창업 3년 미만인 창업지원 사업 참여기업을 대상으로, 창업지원 사업 운영기관의 추천을 통해 별도 선발(10개사 내)',
    ],
  })
})

test('formula operators, negative numbers, ranges and logical qualifiers are preserved', () => {
  const text = '5≤업력<7, 매출≥100억원, 10 > 5, 오차±5%, 산식100×2÷4, 100*2, x**2, -5~10\n(개인)개업연월일 또는 (법인)회사성립연월일 (단, 신산업은 10년 이내)'
  const result = formatCompanyCondition(text)
  assert.equal(result.lead, text.replace('\n', ' '))
  assert.deepEqual(result.notes, [])
  assert.equal(formatCompanyCondition('매출액*0.1 이상\n* 최근 3년 기준').lead, '매출액*0.1 이상')
})

test('labels inside legal clauses and exception parentheses are not rewritten', () => {
  assert.equal(formatCompanyCondition('제2조(신청자격)에 따른 기업(특허 출원 중인 기업 제외)').lead,
    '제 2조(신청자격)에 따른 기업(특허 출원 중인 기업 제외)')
  assert.deepEqual(formatCompanyCondition('□ 업력 7년 이상\n※ 단, 업력 7년 미만 추천기업도 신청대상에 포함'), {
    lead: '업력 7년 이상', notes: ['단, 업력 7년 미만 추천기업도 신청대상에 포함'],
  })
})

test('line bullet boxes are removed while ordinary inline condition parentheses remain', () => {
  const formatted = formatCompanyCondition('☒ 제조업 영위 기업\n□ (개인)사업자 또는 (법인)사업자')
  assert.equal(formatted.lead, '제조업 영위 기업\n(개인)사업자 또는 (법인)사업자')
  assert.equal(formatCompanyCondition('매출 **100억 원 이상**').lead, '매출 100억 원 이상')
})

test('cached generic checklists do not masquerade as industry conditions', () => {
  const checklist = '□ (요건검토) 신청서 및 제출서류 등을 확인하여 신청자격 등 검토\n* 중소기업 여부, 휴폐업·체납 여부, 신용정보, 보조금법 위반, 부적절 업종 등'
  const exclusion = '□ (제외대상) 아래 지원제외 대상에 해당하는 기업\n☒ 도약프로그램 지원사업을 수행 중인 기업\n☒ 국세 또는 지방세를 체납 중인 자(기업)\n한국산업기술진흥원'
  assert.equal(isSpecificIndustryCondition(checklist), false)
  assert.equal(isSpecificIndustryCondition(exclusion), false)
  const value = companyParticipationRows(guide([row('SECTOR', [entry(checklist), entry(exclusion)])]))[3]
  assert.deepEqual(value.mandatory, [])
  assert.equal(entry(exclusion).rule.text, exclusion)
})

test('real industry clauses retain tax or program exceptions', () => {
  for (const text of [
    '제조업(C10~C34) 영위 기업. 단, 국세 체납 기업 제외',
    '유관산업 경험 3년 이상이고 세금 완납',
    '지원제외 업종: 금융업, 보험업 및 사행성 업종',
    'AI 분야 기업 중 국세 체납이 없는 기업',
  ]) assert.equal(isSpecificIndustryCondition(text), true, text)
})


test('a wrapped multiplication operator is not a footnote marker', () => {
  assert.deepEqual(formatCompanyCondition('금액 = 100\n* 2'), {
    lead: '금액 = 100 * 2', notes: [],
  })
  assert.deepEqual(formatCompanyCondition('금액 = 매출액\n* 가중치'), {
    lead: '금액 = 매출액 * 가중치', notes: [],
  })
})

test('explicit excluded industries are still meaningful industry conditions', () => {
  assert.equal(isSpecificIndustryCondition('지원 제외 업종: 유흥주점업, 도박업'), true)
})


test('identical numeric conditions retain their independently supplied program scopes', () => {
  const first = entry('매출액 100억원 이상', 'BASIC', { scope: '점프업100' })
  const second = entry('매출액 100억원 이상', 'BASIC', { scope: '점프업500' })
  const actual = companyParticipationRows(guide([row('REVENUE', [first, second])]))[1]
  assert.deepEqual(actual.mandatory.map(companyConditionScope), ['점프업100', '점프업500'])
  assert.deepEqual(actual.mandatory.map(value => value.rule.text), ['매출액 100억원 이상', '매출액 100억원 이상'])
  assert.equal(companyConditionScope(entry('매출액 1,000억원 이상')), null)
})

test('a source consisting only of footnotes never becomes a fabricated main condition', () => {
  const onlyNote = entry('※ 단, 업력 7년 미만 추천기업도 신청대상에 포함')
  const actual = companyParticipationRows(guide([row('BUSINESS_AGE', [onlyNote])]))[0]
  assert.deepEqual(actual.mandatory, [])
  assert.deepEqual(actual.preference, [])
  assert.equal(actual.evidence[0].rule.sources[0].excerpt, onlyNote.rule.text)
  assert.deepEqual(formatCompanyCondition(onlyNote.rule.text), {
    lead: '', notes: ['단, 업력 7년 미만 추천기업도 신청대상에 포함'],
  })
})


test('the next established body number resumes a condition after a footnote', () => {
  for (const [first, second] of [['①', '②'], ['1.', '2.'], ['가.', '나.']]) {
    const raw = `다음 중 하나 충족\n${first} 업력 3년 미만\n※ 공고일 기준\n${second} 매출 100억원 이상`
    const actual = formatCompanyCondition(raw)
    assert.match(actual.lead, /다음 중 하나 충족/u)
    assert.match(actual.lead, /업력 3년 미만/u)
    assert.match(actual.lead, /매출 100억원 이상/u)
    assert.equal(actual.lead.split('\n').length, 3)
    assert.deepEqual(actual.notes, ['공고일 기준'])
  }
})

test('numbered sublists within a footnote do not become main conditions', () => {
  for (const raw of [
    '※ 참고\n① 추천기업\n② 산정 기준',
    '업력 7년 이상\n※ 참고\n1. 산정 기준\n2. 증빙자료',
    '① 업력 3년 미만\n※ 참고\n1. 산정 기준\n2. 증빙자료',
    '① 업력 3년 미만\n※ 참고\n  ② 산정 기준',
    '1. 업력 3년 미만\n※ 참고\n1. 산정 기준\n2. 증빙자료',
  ]) {
    const actual = formatCompanyCondition(raw)
    assert.doesNotMatch(actual.lead, /추천기업|산정 기준|증빙자료/u, raw)
    assert.match(actual.notes.join(' '), /추천기업|산정 기준/u, raw)
  }
})

test('a less indented continuation resumes after a nested footnote sublist', () => {
  const actual = formatCompanyCondition('① 업력 3년 미만\n※ 참고\n  ② 산정 기준\n② 매출 100억원 이상')
  assert.equal(actual.lead, '업력 3년 미만\n매출 100억원 이상')
  assert.deepEqual(actual.notes, ['참고 산정 기준'])
})

test('amounts decimals and formula prefixes cannot resume a numbered body list', () => {
  const actual = formatCompanyCondition('1. 업력 3년 미만\n※ 참고\n100억원 기준\n2.5억원 산정\n2. × 100\n2. x + 1\n2. 매출 100억원 이상')
  assert.equal(actual.lead, '1. 업력 3년 미만\n2. 매출 100억원 이상')
  assert.deepEqual(actual.notes, ['참고 100억원 기준 2.5억원 산정 2. × 100 2. x + 1'])
})


test('unspaced numbered clauses resume after notes without treating decimals or formulas as list markers', () => {
  for (const [first, second] of [['1.', '2.'], ['가.', '나.']]) {
    const actual = formatCompanyCondition(`${first}업력 3년 미만\n※ 공고일 기준\n2.5억원 산정\n100억원 기준\n2.×100\n${second}매출 100억원 이상`)
    assert.equal(actual.lead, `${first}업력 3년 미만\n${second}매출 100억원 이상`)
    assert.deepEqual(actual.notes, ['공고일 기준 2.5억원 산정 100억원 기준 2.×100'])
  }
})


test('a readable sentence is grounded by its separate original quotation', () => {
  const quote = '제조업(C10~C34)을 주된 사업으로 영위중이어야 함'
  const text = '제조업(C10~C34)을 주된 사업으로 영위해야 합니다.'
  const valid = { ...entry(text, 'BASIC', { sources: [{ excerpt: quote }] }), supportingQuotes: [quote] }
  assert.equal(companyParticipationRows(guide([row('SECTOR', [valid])]))[3].mandatory[0].rule.text, text)
  const invalid = { ...valid, supportingQuotes: ['없는 원문'] }
  assert.equal(companyParticipationRows(guide([row('SECTOR', [invalid])]))[3].mandatory.length, 0)
})

test('submission documents and unrelated fields stay out of every company field including cached data', () => {
  for (const code of ['BUSINESS_AGE', 'REVENUE', 'IP', 'SECTOR']) {
    const values = ['마. 신청서류 [표] 필수 소공인 클린제조환경조성 사업 신청서 자가진단 체크리스트',
      '최근 1년간 표준재무제표증명 제출 필수', '상시근로자 수 10인 미만의 제조업 소상공인']
    const actual = companyParticipationRows(guide([row(code, values.map(value => entry(value)))]))
    assert.equal(actual.flatMap(value => value.mandatory).length, 0)
  }
})

test('old fragments become complete sentences without an API or modifying original evidence', () => {
  assert.equal(companyConditionSentence('제조업(C10~C34)을 주된 사업으로 영위중이어야 함([참고1],[참고2] 참조)', false),
    '제조업(C10~C34)을 주된 사업으로 영위중이어야 합니다.')
  assert.equal(companyConditionSentence('업력 7년 이내', false), '업력 7년 이내여야 합니다.')
  assert.equal(companyConditionSentence('업력 무관', false), '업력 제한이 없습니다.')
  assert.equal(companyConditionSentence('등록 특허 보유 기업', true), '등록 특허 보유 기업을 우대합니다.')
  assert.equal(companyConditionSentence('매출액이 100억원 이상이어야 합니다.', false), '매출액이 100억원 이상이어야 합니다.')
})


test('extended eligibility keeps applicant credit export certification and experience distinct', () => {
  const values = [
    ['BUSINESS_FORM', '공공기관에 해당하는 법인이어야 합니다.'],
    ['CREDIT', '신용평가등급 B 이상이어야 합니다.'],
    ['REVENUE', '전년도 직접수출액 5,000만불 미만이어야 합니다. 단, 글로벌 강소기업 1000+ 지정기업은 5,000만불 이상도 신청 가능합니다.'],
    ['IP', '우수연구개발 기술개발제품 12종에 해당해야 합니다.'],
    ['EXPERIENCE', '공공기관 계약실적은 10억원 이하여야 합니다.'],
    ['SECTOR', '경제안보품목의 도입·생산·제공에 기여해야 합니다.'],
  ]
  const result = companyParticipationRows(guide(values.map(([code, text]) => row(code, [entry(text)]))))
  for (const [code, text] of values) assert.equal(result.find(r => r.code === code).mandatory[0].rule.text, text)
  assert.equal(result.find(r => r.code === 'BUSINESS_AGE').mandatory.length, 0)
})


test('reported mixed eligibility and application-method block yields only a qualification sentence', () => {
  const raw = '신청자격 및 방법\n신청자격 :판로지원법 제2조 제2호에 따른 공공기관\n신청기간 : 상시모집\n신청방법 : 구매정보망(SMPP)을 통한 온라인(인터넷) 신청'
  const value = entry(raw)
  const output = companyParticipationRows(guide([row('BUSINESS_FORM', [value])]))[4]
  assert.equal(output.mandatory.length, 1)
  assert.equal(companyConditionSentence(output.mandatory[0].rule.text, false), '판로지원법 제 2조 제 2호에 따른 공공기관이어야 합니다.')
  assert.equal(output.mandatory[0].rule.sources[0].excerpt, raw)
  assert.equal(companyConditionSentence('신청방법 : 온라인 신청', false), '')
})

test('reported duplicate compact common clauses merge without losing evidence', () => {
  const quote = '중소기업기본법 제2조에 따라 공고일 기준 업력 7년 이상의 중소기업 중 세부 프로그램별 신청요건을 충족하는 기업'
  const good = entry(quote + '이어야 합니다.', 'BASIC', { scope: '공통', sources: [{origin: 'ATTACHMENT', attachmentName: '공고.hwp', excerpt: quote}] })
  good.supportingQuotes = [quote]
  const compact = '공통사항□(신청자격) ' + quote.replace(/\s/gu, '')
  const bad = entry(compact, 'BASIC', { sources: [{origin: 'ATTACHMENT', attachmentName: '공고.pdf', excerpt: compact}] })
  bad.supportingQuotes = [compact]
  const original = structuredClone([good, bad])
  for (const code of ['BUSINESS_AGE', 'BUSINESS_FORM']) {
    const result = companyParticipationRows(guide([row(code, [good, bad])])).find(row => row.code === code)
    assert.equal(result.mandatory.length, 1)
    assert.equal(result.evidence.length, 0)
    assert.equal(result.mandatory[0].rule.sources.length, 2)
    assert.equal(result.mandatory[0].rule.scope, '공통')
    const sentence = companyConditionSentence(result.mandatory[0].rule.text, false, code)
    assert.equal(sentence, code === 'BUSINESS_AGE' ? '공고일 기준 업력이 7년 이상이어야 합니다.' : '중소기업기본법 제 2조에 따라 공고일 기준 업력 7년 이상의 중소기업 중 세부 프로그램별 신청요건을 충족하는 기업이어야 합니다.')
  }
  assert.deepEqual([good, bad], original)
})

test('different thresholds, alternatives and program scopes cannot be deduplicated', () => {
  const items = [entry('매출액 100억원 이상', 'BASIC', {scope: '점프업100'}),
    entry('매출액 100억원 이상', 'BASIC', {scope: '점프업500'}),
    entry('매출액 100억원 이상'), entry('매출액 500억원 이상'),
    entry('업력 3년 이내 또는 매출액 100억원 이상')]
  assert.equal(companyParticipationRows(guide([row('REVENUE', items)]))[1].mandatory.length, 5)
})

test('compact text is restored only from exactly matching readable evidence', () => {
  const raw = '공고일 기준 업력 7년 이상의 중소기업 중 세부 프로그램별 신청요건을 충족하는 기업'
  const compact = raw.replace(/\s/gu, '')
  const value = entry(compact, 'BASIC', {sources: [{origin: 'NOTICE_BODY', excerpt: raw}]})
  const result = companyParticipationRows(guide([row('BUSINESS_AGE', [value])]))[0]
  assert.equal(result.mandatory[0].rule.text, raw)
  const unavailable = companyParticipationRows(guide([row('BUSINESS_AGE', [entry(compact)])]))[0]
  assert.equal(unavailable.mandatory.length, 0)
  assert.equal(unavailable.evidence.length, 1)
})

test('a possible credit check is not displayed as an actual requirement', () => {
  const generic = entry('신용정보 요건 충족 여부가 포함될 수 있다.')
  const specific = entry('기업신용평가등급 B 이상이어야 합니다.')
  const result = companyParticipationRows(guide([row('CREDIT', [generic, specific])]))[6]
  assert.deepEqual(result.mandatory, [specific])
  assert.equal(companyConditionSentence(generic.rule.text, false), '')
})


test('distinct exceptions and multi-quote evidence are not collapsed into one condition', () => {
  const first = entry('업력 7년 이내 기업\n※ 단, 신산업 기업은 10년 이내')
  const second = entry('업력 7년 이내 기업\n※ 단, 신산업 기업은 9년 이내')
  assert.equal(companyParticipationRows(guide([row('BUSINESS_AGE', [first, second])]))[0].mandatory.length, 2)
  const a = entry('매출액 100억원 이상인 기업이어야 합니다.')
  const b = structuredClone(a)
  a.supportingQuotes = ['매출액 100억원 이상', '매출액 500억원 이상']
  b.supportingQuotes = ['매출액 100억원 이상', '매출액 1000억원 이상']
  a.rule.sources = [{origin: 'NOTICE_BODY', excerpt: a.supportingQuotes.join('\n')}]
  b.rule.sources = [{origin: 'NOTICE_BODY', excerpt: b.supportingQuotes.join('\n')}]
  assert.equal(companyParticipationRows(guide([row('REVENUE', [a, b])]))[1].mandatory.length, 2)
})


test('program name is part of the sentence and a purpose paragraph never becomes its label', () => {
  const value = entry('업력 7년 이상의 매출액 100억원 달성 중소기업이 지원 대상입니다.', 'BASIC',
    { scope: '성장형 · (사업목적) 기업의 성장을 지원하여 육성' })
  assert.equal(scopedCompanyConditionSentence(value, 'REVENUE'),
    '성장형 유형은 업력 7년 이상의 매출액 100억원 달성 중소기업이 지원 대상입니다.')
  assert.equal(scopedCompanyConditionSentence(entry('업력 6년 이상', 'BASIC', { scope: '공통' }), 'BUSINESS_AGE'),
    '공통으로 업력 6년 이상이어야 합니다.')
  assert.equal(scopedCompanyConditionSentence(entry('업력 6년 이상'), 'BUSINESS_AGE'), '업력 6년 이상이어야 합니다.')
})


const detailedApplicant = '중소기업기본법 제2조에 따라 공고일 기준 업력 7년 이상의 중소기업 중 세부 프로그램별 신청요건을 충족하는 기업이어야 합니다.'
const shortApplicant = '중소기업기본법 제2조에 따라 공고일 기준 업력 7년 이상의 중소기업이 지원 대상입니다.'
const applicantRows = entries => companyParticipationRows(guide([row('BUSINESS_FORM', entries)]))[4]

test('the reported detailed applicant sentence replaces its summary in either order and retains every original', () => {
  const detailed = entry(detailedApplicant, 'BASIC', { scope: '공통' })
  const short = entry(shortApplicant, 'BASIC', { scope: '공통' })
  const original = structuredClone([detailed, short])
  for (const values of [[short, detailed], [detailed, short]]) {
    const result = applicantRows(values)
    assert.equal(result.mandatory.length, 1)
    assert.equal(result.evidence.length, 0)
    assert.equal(result.mandatory[0].rule.text, detailedApplicant)
    assert.equal(scopedCompanyConditionSentence(result.mandatory[0], 'BUSINESS_FORM'),
      '공통으로 중소기업기본법 제 2조에 따라 공고일 기준 업력 7년 이상의 중소기업 중 세부 프로그램별 신청요건을 충족하는 기업이어야 합니다.')
    assert.deepEqual(new Set(result.mandatory[0].supportingQuotes), new Set([shortApplicant, detailedApplicant]))
    assert.deepEqual(new Set(result.mandatory[0].rule.sources.map(source => source.excerpt)), new Set([shortApplicant, detailedApplicant]))
  }
  assert.deepEqual([detailed, short], original)
})

test('detailed conditions retain separate supporting quotations and all merged evidence', () => {
  const detailed = entry(detailedApplicant, 'BASIC', { scope: '공통' })
  const short = entry(shortApplicant, 'BASIC', { scope: '공통' })
  detailed.supportingQuotes = [detailedApplicant.replace('이어야 합니다.', '')]
  short.supportingQuotes = [shortApplicant.replace('이 지원 대상입니다.', '')]
  const result = applicantRows([short, detailed]).mandatory[0]
  assert.equal(result.rule.text, detailedApplicant)
  assert.deepEqual(new Set(result.supportingQuotes), new Set([...short.supportingQuotes, ...detailed.supportingQuotes]))
})

test('a missing program scope joins only one uniquely matching detailed condition', () => {
  const short = entry(shortApplicant)
  const detailed = entry(detailedApplicant, 'BASIC', { scope: '성장형' })
  const unique = applicantRows([short, detailed])
  assert.equal(unique.mandatory.length, 1)
  assert.equal(unique.mandatory[0].rule.scope, '성장형')
  const alternative = entry(detailedApplicant, 'BASIC', { scope: '도약형' })
  assert.equal(applicantRows([short, detailed, alternative]).mandatory.length, 3)
})

test('a shared summary remains separate when multiple different detailed conditions contain it', () => {
  const short = entry(shortApplicant, 'BASIC', { scope: '공통' })
  const first = entry(detailedApplicant, 'BASIC', { scope: '공통' })
  const second = entry(detailedApplicant.replace('세부 프로그램별 신청요건을 충족하는', '기업부설연구소를 보유한'), 'BASIC', { scope: '공통' })
  for (const values of [[short, first, second], [second, short, first], [first, second, short]]) {
    assert.equal(applicantRows(values).mandatory.length, 3)
  }
})

test('containment chains choose the most detailed condition independently of input order', () => {
  const short = entry('중소기업이 지원 대상입니다.')
  const middle = entry('중소기업 중 연구소를 보유한 기업이어야 합니다.')
  const detailed = entry('중소기업 중 연구소를 보유한 기업 중 세부 신청요건을 충족하는 기업이어야 합니다.')
  for (const values of [[short, middle, detailed], [detailed, short, middle], [middle, detailed, short]]) {
    const result = applicantRows(values)
    assert.equal(result.mandatory.length, 1)
    assert.equal(result.mandatory[0].rule.text, detailed.rule.text)
    assert.equal(result.mandatory[0].rule.sources.length, 3)
  }
})

test('different numbers, comparisons, dates, logical operators and exceptions never merge as details', () => {
  const pairs = [
    ['매출액 100억원 이상 기업', '매출액 500억원 이상 기업 중 연구소 보유 기업'],
    ['업력 7년 이상 기업', '업력 7년 이하 기업 중 연구소 보유 기업'],
    ['공고일 기준 업력 7년 이상 기업', '접수마감일 기준 업력 7년 이상 기업 중 연구소 보유 기업'],
    ['법인사업자 또는 연구소 보유 기업', '법인사업자 및 연구소 보유 기업 중 신청요건 충족 기업'],
    ['중소기업', '중소기업 중 대기업을 제외한 기업'],
    ['중소기업', '중소기업 중 출원 중 특허를 포함하는 기업'],
    ['매출 10억 및 수출 5억 이상 기업', '매출 5억 및 수출 10억 이상 기업 중 연구소 보유 기업'],
    ['중소기업 중 연구소를 보유한 기업', '중소기업 중 연구소를 보유한 기업 또는 대기업'],
  ]
  for (const [short, detailed] of pairs) {
    const values = [entry(short + '이어야 합니다.'), entry(detailed + '이어야 합니다.')]
    assert.equal(applicantRows(values).mandatory.length, 2, `${short} / ${detailed}`)
  }
})

test('same-looking conditions keep different program, audience, mandatory status and explicit exceptions', () => {
  for (const difference of [{ scope: '성장형' }, { audience: '주관기관' }, { exceptions: ['별도 예외 적용'] }]) {
    assert.equal(applicantRows([entry(shortApplicant, 'BASIC', { scope: '공통' }),
      entry(detailedApplicant, 'BASIC', { scope: '공통', ...difference })]).mandatory.length, 2)
  }
  const result = applicantRows([entry(shortApplicant), entry(detailedApplicant, 'PREFERENCE')])
  assert.equal(result.mandatory.length, 1)
  assert.equal(result.preference.length, 1)
})

test('a clipped program label cannot produce a malformed prefix or become an unscoped requirement', () => {
  const text = '경제안보품목 및 경제안보서비스의 원활한 도입·생산 및 제공에 기여하고자 하는 사업자 또는 사업자단체가 지원 대상입니다.'
  const damaged = entry(text, 'BASIC', { scope: '는사업' })
  const result = applicantRows([damaged])
  assert.equal(result.mandatory.length, 0)
  assert.equal(result.evidence.length, 1)
  assert.equal(result.evidence[0].rule.sources[0].excerpt, text)
  assert.equal(scopedCompanyConditionSentence(damaged, 'BUSINESS_FORM'), '')
  assert.equal(scopedCompanyConditionSentence(entry(text, 'BASIC', { scope: '공급망안정화사업' }), 'BUSINESS_FORM'),
    '공급망안정화사업 유형은 ' + text)
})

test('a clipped scope is restored only from one explicitly labelled full program name in evidence', () => {
  const text = '중소기업이 지원 대상입니다.'
  const source = { origin: 'NOTICE_BODY', sectionTitle: '사업 유형: 성장하는사업', excerpt: text }
  const damaged = entry(text, 'BASIC', { scope: '는사업', sources: [source] })
  const restored = applicantRows([damaged]).mandatory[0]
  assert.equal(restored.rule.scope, '성장하는사업')
  assert.equal(restored.rule.sources[0], source)
  assert.equal(damaged.rule.scope, '는사업')
  const ambiguous = entry(text, 'BASIC', { scope: '는사업', sources: [source,
    { ...source, sectionTitle: '사업 유형: 도약하는사업' }] })
  assert.equal(applicantRows([ambiguous]).mandatory.length, 0)
  const unlabelled = entry(text, 'BASIC', { scope: '는사업', sources: [{ ...source, sectionTitle: '성장하는사업' }] })
  assert.equal(applicantRows([unlabelled]).mandatory.length, 0)
})

test('a verified long complete condition keeps its beginning ending and all qualifications', () => {
  const long = '중소기업기본법에 따른 중소기업 중 ' + '신청 분야의 세부 프로그램별 신청요건과 공고에 명시된 참여 요건을 충족하고 '.repeat(9)
    + '기업부설연구소를 보유한 기업이어야 합니다.'
  assert.ok(long.length > 360)
  const result = applicantRows([entry(long)])
  assert.equal(result.mandatory.length, 1)
  assert.equal(companyConditionSentence(result.mandatory[0].rule.text, false, 'BUSINESS_FORM'), long)
  assert.equal(result.mandatory[0].rule.sources[0].excerpt, long)
})


test('required section aliases merge but exclusions never replace ordinary eligibility', () => {
  const basic = entry(shortApplicant, 'BASIC', { scope: '공통' })
  const detailed = entry(detailedApplicant, 'ELIGIBILITY', { scope: '공통' })
  assert.equal(applicantRows([basic, detailed]).mandatory.length, 1)
  const exclusion = entry(detailedApplicant, 'EXCLUSION', { scope: '공통' })
  assert.equal(applicantRows([basic, exclusion]).mandatory.length, 2)
})

test('an unreadable detailed source never displaces a readable shorter condition', () => {
  const compact = detailedApplicant.replace(/\s/gu, '')
  const result = applicantRows([entry(shortApplicant), entry(compact)])
  assert.equal(result.mandatory.length, 1)
  assert.equal(result.mandatory[0].rule.text, shortApplicant)
  assert.equal(result.evidence.length, 1)
})


test('damaged OCR duplicates remain original evidence instead of being certified as readable', () => {
  const prefix = '중소기업기본법 제2조에 따라 공고일 기준 업 력 7년 년 이상 이 상의 의중중소 소기기업'
  const short = entry(prefix + '이 지원 대상입니다.', 'BASIC', { scope: '공통' })
  const detailed = entry(prefix + ' 중 세부 프로그램별 신청요건을 충족하는 기업이어야 합니다.', 'BASIC', { scope: '공통' })
  for (const values of [[short, detailed], [detailed, short]]) {
    const result = applicantRows(values)
    assert.equal(result.mandatory.length, 0)
    assert.equal(result.evidence.length, 1)
    assert.deepEqual(result.evidence[0].rule.sources.map(source => source.excerpt), values.map(value => value.rule.text))
  }
})


test('ordinary section headings never become a program type or an implicit common condition', () => {
  const text = '국내 중소·중견기업이 지원 대상입니다.'
  for (const scope of ['신청자격 · 지원', '공통 · 지원', '신청자격', '지원 조건', '공통사항', '성장형 / 공통 · 지원']) {
    const value = entry(text, 'BASIC', { scope })
    assert.equal(companyConditionScope(value), null, scope)
    assert.equal(scopedCompanyConditionSentence(value, 'BUSINESS_FORM'), '', scope)
    const result = applicantRows([value])
    assert.equal(result.mandatory.length, 0)
    assert.equal(result.evidence[0].rule.sources[0].excerpt, text)
  }
  assert.equal(scopedCompanyConditionSentence(entry(text, 'BASIC', { scope: '공통' }), 'BUSINESS_FORM'), '공통으로 ' + text)
})

test('a real program retains its name while generic section headings remain only in evidence', () => {
  const text = '국내 중소·중견기업이 지원 대상입니다.'
  const value = entry(text, 'BASIC', { scope: '제조AI특화 · 신청자격 · 지원',
    sources: [{ origin: 'NOTICE_BODY', excerpt: text, sectionTitle: '□ 신청자격' }] })
  const result = applicantRows([value]).mandatory[0]
  assert.equal(result.rule.scope, '제조AI특화')
  assert.equal(scopedCompanyConditionSentence(result, 'BUSINESS_FORM'), '제조AI특화 유형은 ' + text)
  assert.equal(result.rule.sources[0].sectionTitle, '□ 신청자격')
  assert.equal(value.rule.scope, '제조AI특화 · 신청자격 · 지원')
})

test('circled list numbers and whitespace normalize equivalent scopes without changing actual type numbers', () => {
  const text = '국내 중소·중견기업이 지원 대상입니다.'
  const a = entry(text, 'BASIC', { scope: '④ 대중소 상생형' })
  const b = entry(text, 'BASIC', { scope: '대중소상생형' })
  const result = applicantRows([a, b])
  assert.equal(result.mandatory.length, 1)
  assert.equal(result.mandatory[0].rule.sources.length, 2)
  assert.doesNotMatch(scopedCompanyConditionSentence(result.mandatory[0], 'BUSINESS_FORM'), /④/u)
  const distinct = ['점프업100', '점프업500', '유형1', '유형2', '대중소 상생형(일반형)', '대중소 상생형(AI형)']
  assert.equal(applicantRows(distinct.map(scope => entry(text, 'BASIC', { scope }))).mandatory.length, distinct.length)
  assert.equal(companyConditionScope(entry(text, 'BASIC', { scope: '① 점프업100' })), '점프업100')
})

test('the supplied applicant role is visible and distinct roles never look like duplicate statements', () => {
  const text = '국내 중소·중견기업이 지원 대상입니다.'
  const values = ['도입기업', '공급기업'].map(audience => entry(text, 'BASIC', { scope: '제조AI특화', audience }))
  const result = applicantRows(values)
  assert.equal(result.mandatory.length, 2)
  assert.deepEqual(result.mandatory.map(value => scopedCompanyConditionSentence(value, 'BUSINESS_FORM')), [
    '제조AI특화 유형의 도입기업은 ' + text,
    '제조AI특화 유형의 공급기업은 ' + text,
  ])
  assert.equal(scopedCompanyConditionSentence(entry(text, 'BASIC', { scope: '공통', audience: '도입기업' }), 'BUSINESS_FORM'),
    '공통으로 도입기업은 ' + text)
})

test('an applicant role already supplied in the sentence is not repeated and labelled roles read naturally', () => {
  const text = '국내 중소·중견 제조기업이 지원 대상입니다.'
  for (const prefix of ['- (도입기업) ', '(도입기업) ', '도입기업은 ']) {
    const value = entry(prefix + text, 'BASIC', { scope: '제조AI특화', audience: '도입기업' })
    assert.equal(scopedCompanyConditionSentence(value, 'BUSINESS_FORM'), '제조AI특화 유형의 도입기업은 ' + text)
    assert.equal(value.rule.sources[0].excerpt, prefix + text)
  }
  const existing = '국내 중소·중견 도입기업이 지원 대상입니다.'
  assert.equal(scopedCompanyConditionSentence(entry(existing, 'BASIC', { scope: '제조AI특화', audience: '도입기업' }), 'BUSINESS_FORM'),
    '제조AI특화 유형에서 ' + existing)
})

test('scope and role display never reclassify or merge distinct source-assigned fields', () => {
  const text = '제조데이터를 수집 중인 국내 중소·중견 제조기업이 지원 대상입니다.'
  const value = entry(text, 'BASIC', { scope: '제조데이터 지원', audience: '도입기업' })
  const rows = companyParticipationRows(guide([row('BUSINESS_FORM', [value]), row('SECTOR', [structuredClone(value)])]))
  assert.equal(rows.find(row => row.code === 'BUSINESS_FORM').mandatory.length, 1)
  assert.equal(rows.find(row => row.code === 'SECTOR').mandatory.length, 1)
})


test('an exact partial scope hierarchy joins one full parent path with identical statement role and evidence', () => {
  const text = '국내 중소·중견기업이 지원 대상입니다.'
  const partial = entry(text, 'BASIC', { scope: '자율형공장', audience: '도입기업' })
  const full = entry(text, 'BASIC', { scope: '스마트공장 구축 · 자율형공장', audience: '도입기업' })
  for (const values of [[partial, full], [full, partial]]) {
    const result = applicantRows(values)
    assert.equal(result.mandatory.length, 1)
    assert.equal(result.mandatory[0].rule.scope, '스마트공장 구축 · 자율형공장')
    assert.equal(result.mandatory[0].rule.sources.length, 2)
    assert.equal(result.evidence.length, 0)
  }
})

test('partial scope hierarchies cannot choose between competing parents or match a text substring', () => {
  const text = '국내 중소·중견기업이 지원 대상입니다.'
  const value = scope => entry(text, 'BASIC', { scope })
  assert.equal(applicantRows([value('자율형공장'), value('스마트공장 구축 · 자율형공장'), value('지역혁신 · 자율형공장')]).mandatory.length, 3)
  assert.equal(applicantRows([value('자율형'), value('스마트공장 구축 · 자율형공장')]).mandatory.length, 2)
  assert.equal(applicantRows([value('대중소상생형(일반형)'), value('스마트공장 구축 · 대중소상생형(AX형)')]).mandatory.length, 2)
})

test('partial hierarchy recovery requires the same actor statement and complete quotation set', () => {
  const text = '국내 중소·중견기업이 지원 대상입니다.'
  const partial = entry(text, 'BASIC', { scope: '자율형공장', audience: '도입기업' })
  const otherActor = entry(text, 'BASIC', { scope: '스마트공장 구축 · 자율형공장', audience: '공급기업' })
  assert.equal(applicantRows([partial, otherActor]).mandatory.length, 2)
  const detail = entry('국내 중소·중견기업 중 연구소를 보유한 기업이 지원 대상입니다.', 'BASIC',
    { scope: '스마트공장 구축 · 자율형공장', audience: '도입기업' })
  assert.equal(applicantRows([partial, detail]).mandatory.length, 2)
  const differentQuotes = entry(text, 'BASIC', { scope: '스마트공장 구축 · 자율형공장', audience: '도입기업',
    sources: [{ origin: 'NOTICE_BODY', excerpt: text + ' 국내 법인사업자' }] })
  differentQuotes.supportingQuotes = [text, '국내 법인사업자']
  assert.equal(applicantRows([partial, differentQuotes]).mandatory.length, 2)
})


test('a role mentioned only as an object or registration requirement does not replace the supplied subject', () => {
  const text = '공급기업과 협력하며 도입기업 등록을 완료한 기업이어야 합니다.'
  const value = entry(text, 'BASIC', { scope: '자율형공장', audience: '도입기업' })
  assert.equal(scopedCompanyConditionSentence(value, 'BUSINESS_FORM'), '자율형공장 유형의 도입기업은 ' + text)
  const target = entry('도입기업을 지원하는 기술을 보유한 기업이어야 합니다.', 'BASIC', { scope: '자율형공장', audience: '도입기업' })
  assert.equal(scopedCompanyConditionSentence(target, 'BUSINESS_FORM'),
    '자율형공장 유형의 도입기업은 도입기업을 지원하는 기술을 보유한 기업이어야 합니다.')
})


test('table presentation separates supplied program context without shortening the complete sentence', () => {
  const text = '중소기업기본법 제2조에 따라 공고일 기준 업력 7년 이상의 중소기업 중 세부 프로그램별 신청요건을 충족하는 기업이어야 합니다.'
  const value = entry(text, 'BASIC', { scope: '공통' })
  assert.deepEqual(companyConditionPresentation(value, 'BUSINESS_FORM'), {
    scope: '공통', audience: null, sentence: text.replace('제2조', '제 2조'), notes: [],
  })
  assert.equal(value.rule.text, text)
})

test('table presentation preserves roles, program alternatives and complete long conditions', () => {
  const text = '국내 중소·중견 제조기업 중 ' + '세부 프로그램별 신청요건을 충족하고 '.repeat(20) + '기업부설연구소를 보유한 기업이어야 합니다.'
  const value = entry(text, 'BASIC', { scope: '① 제조AI · 자율형공장 / AX선도모델', audience: '도입기업' })
  const result = companyConditionPresentation(value, 'BUSINESS_FORM')
  assert.equal(result.scope, '제조AI · 자율형공장 / AX선도모델')
  assert.equal(result.audience, '도입기업')
  assert.equal(result.sentence, '도입기업은 ' + text)
  assert.ok(result.sentence.length > 360)
})

test('table presentation never guesses or strips a scope-like phrase in the supplied condition', () => {
  const value = entry('제조AI 유형은 국내 중소기업이 지원 대상입니다.', 'BASIC', { scope: '제조AI' })
  assert.equal(companyConditionPresentation(value, 'BUSINESS_FORM').sentence, value.rule.text)
  const invalid = entry('국내 중소기업이 지원 대상입니다.', 'BASIC', { scope: '는사업' })
  assert.equal(companyConditionPresentation(invalid, 'BUSINESS_FORM').sentence, '')
})


test('table presentation always retains exception footnotes and measurement dates outside the evidence toggle', () => {
  const text = '□ 업력 7년 이상\n※ 단, 업력 7년 미만 추천기업도 신청대상에 포함\n* 최근 3개년(’23~’25년) 표준재무제표증명원상 매출액 100억원 1회 이상 달성 기업'
  const value = entry(text, 'BASIC', { scope: '공통' })
  const result = companyConditionPresentation(value, 'BUSINESS_AGE')
  assert.equal(result.sentence, '업력 7년 이상이어야 합니다.')
  assert.deepEqual(result.notes, ['단, 업력 7년 미만 추천기업도 신청대상에 포함', '최근 3개년(’23~’25년) 표준재무제표증명원상 매출액 100억원 1회 이상 달성 기업'])
  assert.equal(value.rule.text, text)
})


for (const sample of JSON.parse(readFileSync(new URL('../../backend/src/test/resources/participation-conditions/display-quality.json', import.meta.url), 'utf8'))) {
  test('shared display-quality contract: ' + sample.name, () => {
    assert.equal(isReadableCompanyCondition(sample.text), sample.readable)
    if (!sample.readable) {
      const result = applicantRows([entry(sample.text)])
      assert.equal(result.mandatory.length, 0)
      assert.equal(result.evidence[0].rule.sources[0].excerpt, sample.text)
    }
  })
}


test('meaningful circle glyphs inside eligibility prose survive decoration cleanup', () => {
  for (const text of ['적격(○) 판정을 받은 중소기업이 지원 대상입니다.', '등록 상태가 ●인 중소기업이 지원 대상입니다.']) {
    assert.equal(companyConditionSentence(text, false, 'BUSINESS_FORM'), text)
  }
})


test('explicit program filter retains common and unknown scope and exact alternative programs', () => {
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


test('table notes retain server-supported details and exceptions without repeating the same footnote', () => {
  const note = '최근 3개년(’23~’25년) 매출액 500억원 1회 이상 달성 기업'
  const exception = '창업 3년 미만 추천기업도 신청대상에 포함'
  const value = entry('매출액 500억원 이상\n* ' + note, 'BASIC', {
    details: [note, note.replaceAll(' ', '  ')], exceptions: [exception],
  })
  const result = companyConditionPresentation(value, 'REVENUE')
  assert.deepEqual(result.notes, [note, exception])
  assert.equal(value.rule.details.length, 2)
  assert.equal(value.rule.exceptions[0], exception)
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


test('identical complete display contracts merge different file layouts and retain all original quotations', () => {
  const text = '도시형소공인 지원에 관한 특별법 제2조에 의한 소공인이 지원 대상입니다.'
  const details = ['① 제조업(C10~C34)을 주된 사업으로 영위 ② 상시근로자 10인 미만을 모두 충족']
  const originals = ['◦ 도시형소공인 지원에 관한 특별법 제2조에 의한 소공인\n- ' + details[0],
    '◦ 도시형소공인 지원에 관한 특별법 제2조에 의한 소공인\n[표]\n신청 조건\t' + details[0] + '\n[/표]']
  const entries = originals.map(quote => ({ ...entry(text, 'BASIC', { details, sources: [{ origin: 'ATTACHMENT', excerpt: quote }] }), supportingQuotes: [quote] }))
  const result = applicantRows(entries)
  assert.equal(result.mandatory.length, 1)
  assert.deepEqual(result.mandatory[0].rule.details, details)
  assert.deepEqual(result.mandatory[0].supportingQuotes, originals)
  assert.deepEqual(result.mandatory[0].rule.sources.map(source => source.excerpt), originals)
  for (const change of [
    { details: [details[0].replace('10인', '9인')] },
    { details: [details[0].replace('모두', '하나를')] },
    { exceptions: ['추천 기업은 제외'] },
    { guidance: ['협약 체결 전 확인'] },
    { scope: '별도 유형' }, { audience: '도입기업' }, { section: 'PREFERENCE' },
  ]) {
    const second = structuredClone(entries[1])
    Object.assign(second.rule, change)
    const different = applicantRows([entries[0], second])
    assert.equal(different.mandatory.length + different.preference.length, 2, JSON.stringify(change))
  }
})
