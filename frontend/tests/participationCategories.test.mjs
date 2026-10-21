import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { participationCategories } from '../src/utils/participationCategories.ts'

test('the eight categories include every requested subcategory exactly once', () => {
  assert.equal(participationCategories.length, 8)
  const rows = participationCategories.flatMap(value => value.rows)
  assert.equal(rows.length, 40)
  assert.equal(new Set(rows.map(value => value.code)).size, 40)
  assert.deepEqual(participationCategories.map(value => value.rows.length), [6, 5, 5, 4, 3, 7, 4, 6])
})

test('browser and backend share the same category codes, order and labels', () => {
  const java = readFileSync('../backend/src/main/java/com/publicmonitor/backend/domain/document/service/ParticipationCriterion.java', 'utf8')
  const definitions = [...java.matchAll(/^\s+(\w+)\("([^"]+)", "([^"]+)", "([^"]+)",/gm)]
  assert.deepEqual(definitions.map(match => [match[1], match[2], match[3], match[4]]),
    participationCategories.flatMap(category => category.rows.map(row => [row.code, category.code, category.label, row.label])))
})
