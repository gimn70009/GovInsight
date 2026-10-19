import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { filterSettingsOptions, parsePreparationDays, parseUrgencyDays, validateEvaluationSettings } from '../src/utils/evaluationSettings.ts'

const fixture = () => JSON.parse(readFileSync(new URL('../../backend/src/main/resources/opportunity-settings-defaults.json', import.meta.url), 'utf8'))
const catalog = JSON.parse(readFileSync(new URL('../../backend/src/main/resources/opportunity-settings-catalog.json', import.meta.url), 'utf8'))

test('산업은 두 분야로 제한하고 기술 검색은 다른 분야와 동의어를 포함한다', () => {
  assert.deepEqual(catalog.industries.map(group => group.name), ['전자·첨단제조', '기계·모빌리티'])
  assert.deepEqual(filterSettingsOptions(catalog.technologies, '제조·공정', ' LLM '), ['대규모 언어모델'])
  assert.deepEqual(filterSettingsOptions(catalog.technologies, '제조·공정', '인공지능'), ['AI 에이전트', '생성형 AI'])
  assert.deepEqual(filterSettingsOptions(catalog.technologies, '제조·공정', ''), catalog.technologies[2].options)
  assert.deepEqual(filterSettingsOptions(catalog.industries, '전자·첨단제조', '없는 항목'), [])
})

test('신청 준비 기간은 미정 또는 1~365일의 정수로 입력하고 잘못된 값은 저장하지 않는다', () => {
  for (const raw of ['', '1', '3', '10', '18', '21', '365']) {
    const data = fixture()
    data.criteria.preparationDays = parsePreparationDays(raw)
    assert.equal(data.criteria.preparationDays, raw === '' ? null : Number(raw))
    assert.equal(validateEvaluationSettings(data), '')
  }
  for (const raw of ['0', '366', '-1', '1.5', '1e2', 'abc', ' ', '999999999999999999999']) {
    const data = fixture()
    data.criteria.preparationDays = parsePreparationDays(raw)
    assert.match(validateEvaluationSettings(data), /신청 준비 기간/)
  }
})

test('긴급도 기준은 1~365일의 정수를 직접 입력하고 빈 값이나 소수는 저장하지 않는다', () => {
  for (const raw of ['1', '3', '7', '10', '18', '365']) {
    const data = fixture()
    data.criteria.urgencyDays = parseUrgencyDays(raw)
    assert.equal(data.criteria.urgencyDays, Number(raw))
    assert.equal(validateEvaluationSettings(data), '')
  }
  for (const raw of ['', '0', '-1', '366', '1.5', '1e2', 'abc', ' ']) {
    const data = fixture()
    data.criteria.urgencyDays = parseUrgencyDays(raw)
    assert.match(validateEvaluationSettings(data), /우선 확인 기간/)
  }
})

test('정상 기본값은 저장 가능하고 회사명과 빈 선택은 거부한다', () => {
  assert.equal(validateEvaluationSettings(fixture()), '')
  const blank = fixture(); blank.company.name = '  '
  assert.match(validateEvaluationSettings(blank), /회사명/)
  const empty = fixture(); empty.criteria.tasks = []
  assert.match(validateEvaluationSettings(empty), /1개 이상/)
})

test('목표 제한과 미래 설립일, 미완성 실적 및 인증을 검증한다', () => {
  const goals = fixture(); goals.criteria.goals.push('목표 추가')
  assert.match(validateEvaluationSettings(goals), /최대 3개/)
  const future = fixture(); future.company.establishedOn = '2999-01-01'
  assert.match(validateEvaluationSettings(future), /설립일/)
  const project = fixture(); project.company.projects = [{ title: '', description: '', status: 'IN_PROGRESS' }]
  assert.match(validateEvaluationSettings(project), /프로젝트명/)
  const cert = fixture(); cert.company.certifications = [{ name: ' ', status: 'HELD', expiresOn: null }]
  assert.match(validateEvaluationSettings(cert), /인증·자격/)
})
