import assert from 'node:assert/strict'
import test from 'node:test'
import { explainParticipationReview, groupParticipationRules } from '../src/utils/participationGuide.ts'

const row = (extra = {}) => ({ section: 'REVIEW', label: '참여 자격', text: '전담인력 5인 이상, 경력 3년 이상',
  scope: null, audience: null, details: [], exceptions: [], guidance: [], outcomes: [],
  sources: [{ origin: 'NOTICE_BODY', excerpt: '전담인력 5인 이상, 경력 3년 이상' }], ...extra })

test('explains actual resolver causes independently from topical keywords or thresholds', () => {
  const content = row({ reviewReason: 'SOURCE_CONTEXT_UNCONFIRMED' })
  const result = explainParticipationReview(content)
  assert.equal(result.title, '전담인력·경력 요건')
  assert.match(result.reason, /연결된 문맥/)
  assert.match(result.reason, /직급별 인원수와 경력 기준/)
  assert.doesNotMatch(result.reason, /인원수가 없|경력이 없|표에서|미완성|상충/)
  const classification = explainParticipationReview({ ...content, reviewReason: 'CLASSIFICATION_UNCLEAR' })
  assert.match(classification.reason, /표에서.*필수·우대·제외/)
  assert.notEqual(classification.reason, result.reason)
  const expert = explainParticipationReview(row({ text: '전문가단 30명 이상 구성 및 프로그램 운영', reviewReason: 'SOURCE_CONTEXT_UNCONFIRMED' }))
  assert.equal(expert.title, '전문가단 구성·운영 요건')
  assert.match(expert.reason, /신청 시.*선정 후/)
})

test('table mapping, incomplete sentences, mixed clauses and exclusions have distinct grounded explanations', () => {
  const table = explainParticipationReview(row({ text: '표에서 적용 대상별 자격 요건을 구분하지 못했습니다.',
    sources: [{ excerpt: '공공· 기관과 민간· 기관의 법령 및 신용등급 기준' }] }))
  assert.equal(table.title, '기관 유형별 신청 자격')
  assert.match(table.reason, /공공기관·민간기관.*연결/)
  for (const [reason, expected] of [['INCOMPLETE_TEXT', /문장이 끝까지/], ['MIXED_REQUIREMENT', /한 항목에 섞여/],
    ['PREFERENCE_NEGATED', /적용하지 않는다는/]]) {
    assert.match(explainParticipationReview(row({ reviewReason: reason })).reason, expected)
  }
  const conflict = explainParticipationReview(row({ reviewReason: 'CONFLICTING_CRITERIA',
    outcomes: [{ variant: '유형1', status: 'ELIGIBLE' }, { variant: '유형1', status: 'INELIGIBLE' }] }))
  assert.equal(conflict.title, '유형1 신청 기준')
  assert.match(conflict.reason, /서로 다른.*기준/)
})

test('legacy or unknown reasons do not fabricate failures based on row or unrelated source keywords', () => {
  for (const reviewReason of [undefined, 'FUTURE_REASON']) {
    const result = explainParticipationReview(row({ reviewReason, sources: [{ excerpt: '표에서 필수 가점 제외 조건이 있는 별도 기준' }] }))
    assert.match(result.reason, /현재 안내.*적용 구분/)
    assert.doesNotMatch(result.reason, /표에서|섞여|문장이 끝까지|서로 다른/)
  }
})

test('review presentation retains exact sources, exceptions, facts and legacy cause metadata', () => {
  const input = row({ reviewReason: 'SOURCE_CONTEXT_UNCONFIRMED', exceptions: ['단, 별도 기관은 3인 이상'], details: ['마감일 기준'] })
  const before = structuredClone(input)
  const shown = groupParticipationRules({ rules: [input] })[0].groups[0].labels[0].rules[0]
  assert.notEqual(shown.review.title, input.sources[0].excerpt)
  assert.notEqual(shown.review.reason, input.sources[0].excerpt)
  assert.deepEqual(shown.sources, input.sources)
  assert.deepEqual(shown.exceptions, input.exceptions)
  assert.deepEqual(shown.details, input.details)
  assert.deepEqual(input, before)
  const legacy = groupParticipationRules({ conditions: [{ kind: 'REVIEW_REQUIRED', category: input.label, text: input.text,
    details: input.details, source: input.sources[0], reviewReason: input.reviewReason }] })
  assert.match(legacy[0].groups[0].labels[0].rules[0].review.reason, /연결된 문맥/)
})
