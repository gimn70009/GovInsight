import assert from 'node:assert/strict'
import test from 'node:test'
import { sourceSettingsChanges, validCollectionCount } from '../src/utils/monitoringSourceSettings.ts'

const sources = [
  { sourceId: 1, detailFetchCount: 2, enabled: true },
  { sourceId: 2, detailFetchCount: 7, enabled: false },
]

test('only changed institutions enter the single save request, including both settings', () => {
  assert.deepEqual(sourceSettingsChanges(sources, { 1: { detailFetchCount: '5', enabled: false } }), {
    updates: [{ sourceId: 1, detailFetchCount: 5, enabled: false }], invalidSourceIds: [], dirtyCount: 1,
  })
  assert.equal(sources[0].detailFetchCount, 2)
})

test('returning drafts to their saved values leaves nothing to save', () => {
  assert.equal(sourceSettingsChanges(sources, { 2: { detailFetchCount: '07', enabled: false } }).dirtyCount, 0)
})

test('an invalid count stays an unsaved change and blocks the batch', () => {
  for (const value of ['', ' ', '0', '-1', '2.5', '2147483648', 'NaN']) {
    assert.equal(validCollectionCount(value), false)
    const changes = sourceSettingsChanges(sources, { 1: { detailFetchCount: value, enabled: true } })
    assert.deepEqual(changes.invalidSourceIds, [1])
    assert.equal(changes.dirtyCount, 1)
    assert.deepEqual(changes.updates, [])
  }
})
