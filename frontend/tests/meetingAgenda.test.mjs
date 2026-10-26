import assert from 'node:assert/strict'
import test from 'node:test'
import { visibleMeetingAgenda } from '../src/utils/meetingAgenda.ts'

test('old generated file checks stay out of the meeting agenda', () => {
  const decisions = ['참여 역할을 결정합니다.', '제출 여부 확인 담당자를 결정합니다.']
  const packed = '제출 여부 확인: 계획서 — 원문을 확인합니다.\n제출 여부 확인: 확약서 — 원문을 확인합니다.'
  assert.deepEqual(visibleMeetingAgenda([...decisions, packed, '  제출 여부 확인: 재무제표']), decisions)
})

test('ordinary agenda entries remain unique and missing agendas are empty', () => {
  const decision = '담당자가 제출 여부 확인: 자료를 대조합니다.'
  assert.deepEqual(visibleMeetingAgenda([decision, decision]), [decision])
  assert.deepEqual(visibleMeetingAgenda(), [])
})
