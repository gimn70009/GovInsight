import assert from 'node:assert/strict'
import test from 'node:test'
import { inspectedSource, inspectSources, proposalSourceKey } from '../src/utils/proposalInspection.ts'

const source = (id, name = '사업계획서(하반기 공고).hwp') => ({
  attachmentId: id, partIndex: 0, fileName: name, attachmentName: name,
  available: true, reason: '',
})
const writable = { status: 'WRITABLE', sectionTitles: ['사업 필요성'], message: '' }

test('a parsed file is selectable only after verified narrative headings arrive', () => {
  const plan = source(1)
  assert.equal(inspectedSource(plan).available, false)
  assert.equal(inspectedSource(plan, writable).available, true)
  assert.equal(inspectedSource(plan, { ...writable, sectionTitles: [] }).available, false)
  assert.equal(inspectedSource(plan, { status: 'UNAVAILABLE', sectionTitles: [], message: 'retry' }).available, false)
  assert.equal(inspectedSource({ ...plan, available: false, reason: 'parse failed' }, writable).reason, 'parse failed')
})

test('FAQ rejection and inspection failures remain distinct and never use filename rules', async () => {
  const results = new Map()
  await inspectSources([source(1), source(2, 'FAQ.pdf'), source(3)],
    async (item) => {
      if (item.attachmentId === 1) return writable
      if (item.attachmentId === 2) return { status: 'NOT_WRITABLE', sectionTitles: [], message: '작성란 없음' }
      throw new Error('network')
    },
    (item, result) => results.set(proposalSourceKey(item), result),
    new AbortController().signal)
  assert.equal(results.get('1:0').status, 'WRITABLE')
  assert.equal(results.get('2:0').status, 'NOT_WRITABLE')
  assert.equal(results.get('3:0').status, 'UNAVAILABLE')
})

test('at most two inspections run together and parse failures are skipped', async () => {
  let running = 0
  let maximum = 0
  let total = 0
  await inspectSources([source(1), source(2), source(3), { ...source(4), available: false }],
    async () => {
      running++
      maximum = Math.max(maximum, running)
      await new Promise((resolve) => setTimeout(resolve, 5))
      running--
      total++
      return writable
    }, () => {}, new AbortController().signal)
  assert.equal(maximum, 2)
  assert.equal(total, 3)
})

test('leaving a document discards late results and does not start queued checks', async () => {
  const controller = new AbortController()
  let calls = 0
  let published = 0
  const wait = []
  const done = inspectSources([source(1), source(2), source(3)], () => {
    calls++
    return new Promise((resolve) => wait.push(resolve))
  }, () => published++, controller.signal)
  controller.abort()
  for (const resolve of wait) resolve(writable)
  await done
  assert.equal(calls, 2)
  assert.equal(published, 0)
})

test('a malformed positive response becomes a retryable failure', async () => {
  let result
  await inspectSources([source(1)], async () => ({ ...writable, sectionTitles: [] }),
    (_, value) => { result = value }, new AbortController().signal)
  assert.equal(result.status, 'UNAVAILABLE')
})
