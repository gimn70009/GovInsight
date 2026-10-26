import assert from 'node:assert/strict'
import test from 'node:test'
import { applicationTypeSentence, groupParticipationRules } from '../src/utils/participationGuide.ts'

const source = { origin: 'NOTICE_BODY', excerpt: '확인된 원문' }
const row = (extra = {}) => ({ section: 'BASIC', label: '참여 자격', text: '신청 기준', scope: null, audience: null,
  details: [], exceptions: [], guidance: [], outcomes: [], sources: [source], ...extra })

test('organizes by application effect and preserves fund scopes and every clause', () => {
  const rules = [row({ section: 'EXCLUSION' }), row({ scope: '매칭융자' }),
    row({ section: 'TARGETED' }), row({ scope: '혁신자금' }), row({ scope: '매칭융자', label: '업력' }),
    row({ section: 'PREFERENCE' }), row({ section: 'REVIEW' })]
  const sections = groupParticipationRules({ rules })
  assert.deepEqual(sections.map(section => section.key), ['BASIC', 'TARGETED', 'EXCLUSION', 'PREFERENCE', 'REVIEW'])
  assert.deepEqual(sections[0].groups.map(group => group.scope), ['매칭융자', '혁신자금'])
  assert.deepEqual(sections[0].groups[0].labels.map(group => group.label), ['참여 자격', '업력'])
  assert.equal(sections.flatMap(section => section.groups.flatMap(group => group.labels.flatMap(label => label.rules))).length, rules.length)
})

test('a merged rule retains multiple sources, outcomes and visible exceptions', () => {
  const rule = row({ section: 'TARGETED', audience: '기존 수요기업', exceptions: ['단, 예외'],
    outcomes: [{ variant: '유형1', status: 'INELIGIBLE' }, { variant: '유형2', status: 'ELIGIBLE' }],
    sources: [source, { ...source, origin: 'ATTACHMENT', attachmentName: '공고.pdf' }] })
  assert.equal(groupParticipationRules({ rules: [rule] })[0].groups[0].labels[0].rules[0], rule)
})

test('unknown classifications require review; empty text and missing sources are not verified rules', () => {
  const sections = groupParticipationRules({ rules: [row({ text: ' ' }), row({ sources: [] }), row({ section: 'NEW' })] })
  assert.equal(sections.length, 1)
  assert.equal(sections[0].key, 'REVIEW')
  assert.equal(sections[0].groups[0].labels[0].rules.length, 1)
})

test('legacy responses and empty new responses remain compatible without reviving removed rows', () => {
  const conditions = [{ kind: 'MANDATORY', category: '업력', text: '업력 7년 이내', scope: null, details: ['기준일'], source }]
  assert.equal(groupParticipationRules({ conditions })[0].groups[0].labels[0].rules[0].text, '업력 7년 이내')
  assert.deepEqual(groupParticipationRules({ conditions, rules: [] }), [])
  assert.deepEqual(groupParticipationRules(), [])
})

const reviewRows = rules => groupParticipationRules({ rules }).flatMap(section => section.groups.flatMap(group => group.labels.flatMap(label => label.rules)))

test('review notices replace generated diagnostics with exact table or conflicting excerpts and keep all sources', () => {
  const table = '구분 | 적용 기준\n공공기관 | 전담인력 3명 이상\n민간기관 | 전담인력 5명 이상'
  const sources = [{ ...source, excerpt: table }, { ...source, origin: 'ATTACHMENT', attachmentName: '별첨.pdf', excerpt: table }]
  const input = row({ section: 'REVIEW', text: '표에서 적용 대상별 자격 요건을 구분하지 못했습니다.', sources, scope: '주관기관' })
  const actual = reviewRows([input])[0]
  assert.equal(actual.text, table)
  assert.equal(actual.scope, '주관기관')
  assert.deepEqual(actual.sources, sources)
  assert.equal(input.text, '표에서 적용 대상별 자격 요건을 구분하지 못했습니다.')
  const conflict = row({ section: 'REVIEW', text: '원문에서 같은 대상·유형의 신청 기준이 서로 다르게 확인됩니다.',
    sources: [{ ...source, excerpt: '기존 기업은 유형1에 신청할 수 있습니다.' }, { ...source, excerpt: '기존 기업은 유형1에 신청할 수 없습니다.' }] })
  assert.equal(reviewRows([conflict])[0].text, conflict.sources.map(value => value.excerpt).join('\n\n'))
})

test('review notices retain incomplete original conditions, exceptions and meaningful details without repeated diagnostics', () => {
  const repeated = '적용 대상이나 기준을 확정하지 못한 항목입니다. 원문에서 해당 조건을 확인해 주세요.'
  const input = row({ section: 'REVIEW', text: '자격 조건의 문장이 끝까지 확인되지 않아 원문 확인이 필요합니다.',
    details: ['전담인력 5인 이상 중', repeated, '마감일 기준'], exceptions: ['단, 예외 대상은 3인 이상'], guidance: [repeated, '첨부의 인력 기준을 확인하세요.'] })
  const actual = reviewRows([input])[0]
  assert.equal(actual.text, '전담인력 5인 이상 중')
  assert.deepEqual(actual.details, ['마감일 기준'])
  assert.deepEqual(actual.exceptions, input.exceptions)
  assert.deepEqual(actual.guidance, ['첨부의 인력 기준을 확인하세요.'])
  assert.equal(input.details.length, 3)
  assert.equal(reviewRows([row({ section: 'REVIEW', text: '기관에서 자격을 확인하지 못한 기업은 신청할 수 없습니다.' })])[0].text,
    '기관에서 자격을 확인하지 못한 기업은 신청할 수 없습니다.')
})

test('legacy review conditions use actual excerpts while empty or unsourced diagnostics do not become rows', () => {
  const text = '표에서 적용 대상별 자격 요건을 구분하지 못했습니다.'
  const actualSource = { ...source, excerpt: '전문가 30명 이상을 확보해야 합니다.' }
  const result = groupParticipationRules({ conditions: [{ kind: 'REVIEW_REQUIRED', category: '참여 자격', text, source: actualSource }] })
  assert.equal(result[0].groups[0].labels[0].rules[0].text, actualSource.excerpt)
  assert.deepEqual(reviewRows([row({ section: 'REVIEW', text, sources: [{ ...source, excerpt: text }] }),
    row({ section: 'REVIEW', text: null }), row({ section: 'REVIEW', sources: [] })]), [])
})


test('application types form one sentence with the audience, explicit exclusivity and verified meanings', () => {
  const item = row({ section: 'TARGETED', audience: '2025년 MRV 사업 수요기업',
    text: '해당 기업은 신청 대상으로 표시된 유형에 한해 신청할 수 있습니다.',
    outcomes: [{ variant: '유형1', status: 'INELIGIBLE' }, { variant: '유형2', status: 'ELIGIBLE' }],
    scope: '사업 A', details: ['신청 마감일 기준'], exceptions: ['별도 명시된 예외'], sources: [source] })
  const saved = JSON.stringify(item)
  assert.equal(applicationTypeSentence(item, [{ name: '유형1', description: '인프라 구축' }, { name: '유형2', description: '제3자 검증' }]),
    '2025년 MRV 사업 수요기업은 유형2(제3자 검증)에만 신청할 수 있으며, 유형1(인프라 구축)에는 신청할 수 없습니다.')
  assert.equal(JSON.stringify(item), saved)
})

test('one permitted type never implies exclusivity or a prohibition for unspecified types', () => {
  const item = row({ section: 'TARGETED', audience: '참여 단체',
    outcomes: [{ variant: '유형2', status: 'ELIGIBLE' }] })
  assert.equal(applicationTypeSentence(item, [{ name: '유형1', description: '별도 지원' }]), '참여 단체는 유형2에 신청할 수 있습니다.')
  assert.equal(applicationTypeSentence({ ...item, text: '유형2에 한해 신청할 수 있습니다.' }), '참여 단체는 유형2에만 신청할 수 있습니다.')
  assert.equal(applicationTypeSentence({ ...item, audience: null, outcomes: [{ variant: '유형1', status: 'INELIGIBLE' }] }),
    '유형1에는 신청할 수 없습니다.')
})

test('missing or ambiguous type descriptions are not invented and review outcomes are not presented as permissions', () => {
  const item = row({ section: 'TARGETED', audience: '신청기업',
    outcomes: [{ variant: '유형1', status: 'INELIGIBLE' }, { variant: '유형2', status: 'INELIGIBLE' }] })
  assert.equal(applicationTypeSentence(item, [{ name: '유형1', description: '지원 A' }, { name: '유형1', description: '지원 B' }]),
    '신청기업은 유형1, 유형2에는 신청할 수 없습니다.')
  assert.equal(applicationTypeSentence({ ...item, section: 'REVIEW' }), null)
  assert.equal(applicationTypeSentence({ ...item, outcomes: [] }), null)
  assert.equal(applicationTypeSentence({ ...item, outcomes: [...item.outcomes, { variant: '유형1', status: 'ELIGIBLE' }] }), null)
})


test('deductions stay separate from eligibility, exclusions and positive bonus points, including legacy conditions', () => {
  const sections = groupParticipationRules({ rules: [row({ section: 'DEDUCTION', label: '평가 감점', text: '납부기한 내 미납 시 1점 감점합니다.' }), row({ section: 'PREFERENCE' })] })
  assert.deepEqual(sections.map(section => section.key), ['PREFERENCE', 'DEDUCTION'])
  assert.equal(sections[1].title, '감점 기준')
  const legacy = groupParticipationRules({ conditions: [{ kind: 'DEDUCTION', category: '평가 감점', text: '1점 감점합니다.', details: ['최대 3점'], source }] })
  assert.equal(legacy[0].key, 'DEDUCTION')
  assert.deepEqual(legacy[0].groups[0].labels[0].rules[0].details, ['최대 3점'])
})

test('unreadable score tables explain the missing row connections without displaying a raw table as the headline', () => {
  const rule = reviewRows([row({ section: 'REVIEW', label: '평가 가점', reviewReason: 'TABLE_MAPPING_UNCLEAR', text: '가점 표 확인', sources: [{ ...source, excerpt: '구분 가점사항 가점 증빙 1 기업 2점' }] })])[0]
  assert.equal(rule.review.title, '평가 가점의 항목별 인정 기준')
  assert.match(rule.review.reason, /조건·점수·증빙/)
  assert.equal(rule.sources[0].excerpt, '구분 가점사항 가점 증빙 1 기업 2점')
})


test('eligibility includes applicant prerequisites and excludes all cost and evaluation content, including unresolved score tables', () => {
  const rules = [row({ text: '자부담 확보가 가능한 기업만 신청 가능합니다.' }),
    row({ section: 'FUNDING', label: '자부담·사업비', text: '자부담 20%를 부담합니다.', details: ['현물 인정'], exceptions: ['비영리기관 면제'] }),
    row({ section: 'PREFERENCE' }), row({ section: 'DEDUCTION' }),
    row({ section: 'REVIEW', label: '평가 가점', reviewReason: 'TABLE_MAPPING_UNCLEAR' }),
    row({ section: 'REVIEW', label: '참여기관 구성', reviewReason: 'TABLE_MAPPING_UNCLEAR' })]
  const flatten = domain => groupParticipationRules({ rules }, domain).flatMap(section => section.groups.flatMap(group => group.labels.flatMap(label => label.rules)))
  assert.deepEqual(flatten('eligibility').map(rule => rule.section), ['BASIC', 'REVIEW'])
  assert.equal(flatten('eligibility')[0].text, rules[0].text)
  assert.deepEqual(flatten('funding')[0], rules[1])
  assert.deepEqual(flatten('evaluation').map(rule => rule.section), ['PREFERENCE', 'DEDUCTION', 'REVIEW'])
})

test('legacy funding stays separate from eligibility, and a cost-only notice cannot appear to have confirmed applicant qualifications', () => {
  const guide = { conditions: [{ kind: 'FUNDING', category: '자부담·사업비', text: '사업비 30% 부담', source }] }
  assert.deepEqual(groupParticipationRules(guide, 'eligibility'), [])
  assert.equal(groupParticipationRules(guide, 'funding')[0].key, 'FUNDING')
})


test('unconfirmed preferences remain in evaluation while genuinely mixed applicant requirements remain in eligibility', () => {
  const rules = [row({ section: 'REVIEW', reviewReason: 'INCOMPLETE_TEXT', text: '해외 수상 기업 우대에 관한', sources: [{ ...source, sectionTitle: '우대사항' }] }),
    row({ section: 'REVIEW', reviewReason: 'MIXED_REQUIREMENT', text: '중소기업이며 특허 보유기업 우대', sources: [{ ...source, sectionTitle: '신청자격' }] })]
  assert.equal(groupParticipationRules({ rules }, 'evaluation')[0].groups[0].labels[0].rules[0].reviewReason, 'INCOMPLETE_TEXT')
  assert.equal(groupParticipationRules({ rules }, 'eligibility')[0].groups[0].labels[0].rules[0].reviewReason, 'MIXED_REQUIREMENT')
})
