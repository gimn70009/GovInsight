import assert from 'node:assert/strict'
import test from 'node:test'
import { groupParticipationRules } from '../src/utils/participationGuide.ts'

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
