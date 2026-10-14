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
