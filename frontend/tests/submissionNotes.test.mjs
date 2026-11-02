import assert from 'node:assert/strict'
import test from 'node:test'
import { submissionNoteGroups, submissionNoteKey } from '../src/utils/submissionNotes.ts'

const source = { origin: 'ATTACHMENT', attachmentName: '공고문.pdf', sectionTitle: '신청서류', excerpt: '신청서\n대표자 서명 필수' }
const application = { title: '신청서', source }
const local = { text: '대표자 서명이 필요합니다.', appliesTo: '신청서', source }
const common = { text: '모든 제출서류는 PDF로 제출합니다.', source }

test('common instructions stay above the table and document instructions move to their own row', () => {
  const docs = [application, { title: '사업계획서', source }]
  assert.deepEqual(submissionNoteGroups(docs, [common, local]), {
    common: [common], byDocument: [[local], []], unassigned: [],
  })
})

test('a scoped note without a matching row is preserved separately, never promoted to common', () => {
  assert.deepEqual(submissionNoteGroups([], [local]), { common: [], byDocument: [], unassigned: [local] })
})

test('exact scope and source prevent similar document names or other attachments from borrowing a note', () => {
  const docs = [{ title: '공동 신청서', source }, { ...application, source: { ...source, attachmentName: '다른 공고.pdf' } }]
  const groups = submissionNoteGroups(docs, [local])
  assert.deepEqual(groups.byDocument, [[], []])
  assert.deepEqual(groups.unassigned, [local])
})

test('the source excerpt disambiguates the same document in application and agreement stages', () => {
  const docs = [application, { ...application, stage: 'AGREEMENT', source: { ...source, excerpt: '협약서류\n신청서\n협약 체결 후 제출' } }]
  assert.deepEqual(submissionNoteGroups(docs, [local]).byDocument, [[local], []])
  const ambiguous = { ...local, source: { ...source, excerpt: '서명 필수 안내' } }
  assert.deepEqual(submissionNoteGroups(docs, [ambiguous]).unassigned, [ambiguous])
})

test('normalized full names and explicit type-qualified scope match without splitting compound names', () => {
  const doc = { ...application, title: '중소기업(소상공인) 확인서', documentNames: ['중소기업(소상공인) 확인서'], appliesTo: '유형1' }
  const note = { ...local, appliesTo: '중소기업(소상공인)확인서 · 유형1' }
  assert.deepEqual(submissionNoteGroups([doc], [note]).byDocument, [[note]])
})

test('diagnostics and empty evidence remain hidden, including after moving notes into rows', () => {
  const notes = [{ ...local, source: { ...source, sectionTitle: '제출서류 표 확인 필요' } }, { ...local, source: { ...source, excerpt: ' ' } }, { ...common, text: ' ' }]
  assert.deepEqual(submissionNoteGroups([application], notes), { common: [], byDocument: [[]], unassigned: [] })
})

test('duplicate display comparison retains dates, negation and quantities', () => {
  assert.equal(submissionNoteKey('* 접수일 기준 90일 이상 유효.'), submissionNoteKey('접수일 기준 90일 이상 유효'))
  assert.notEqual(submissionNoteKey('유효기간 1.5개월'), submissionNoteKey('유효기간 15개월'))
  assert.notEqual(submissionNoteKey('제출 면제'), submissionNoteKey('제출 필수'))
})

test('repeated names within one table use row locations and do not duplicate notes across rows', () => {
  const quote = '신청서 개인 서명\n신청서 단체 날인\n* 원본 제출'
  const docs = ['표의 2행', '표의 3행'].map(location => ({ ...application, source: { ...source, location, excerpt: quote } }))
  const notes = docs.map(doc => ({ ...local, source: doc.source }))
  assert.deepEqual(submissionNoteGroups(docs, notes).byDocument, [[notes[0]], [notes[1]]])
})

test('notice-body evidence matches across nullable and omitted legacy attachment names', () => {
  const doc = { ...application, source: { origin: 'NOTICE_BODY', excerpt: '신청서 제출' } }
  const note = { ...local, source: { ...doc.source, attachmentName: null } }
  assert.deepEqual(submissionNoteGroups([doc], [note]).byDocument, [[note]])
})
