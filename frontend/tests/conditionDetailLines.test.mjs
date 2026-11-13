import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { conditionDetailLines } from '../src/utils/conditionDetailLines.ts'

describe('condition detail layout preserves meaning', () => {
  it('breaks explicit semicolon alternatives without removing OR or punctuation', () => {
    assert.deepEqual(conditionDetailLines('자격 A; 자격 B; 또는 동등 자격'),
      ['자격 A;', '자격 B;', '또는 동등 자격'])
  })
  it('keeps grouped text and law punctuation intact', () => {
    assert.deepEqual(conditionDetailLines('증빙(계약서; 납품서); 「규정; 별표」에 따름'),
      ['증빙(계약서; 납품서);', '「규정; 별표」에 따름'])
  })
  it('removes only a leading list decoration, never a negative value or condition', () => {
    assert.deepEqual(conditionDetailLines('* 별도 상근 인력이 있으면 겸직 가능'), ['별도 상근 인력이 있으면 겸직 가능'])
    assert.deepEqual(conditionDetailLines('-5% 미만; 2 * 3 이상의 실적'), ['-5% 미만;', '2 * 3 이상의 실적'])
  })
  it('does not merge explanations, rewrite fragments or parse markup', () => {
    assert.deepEqual(conditionDetailLines('허용하지 않음'), ['허용하지 않음'])
    assert.deepEqual(conditionDetailLines('<script>원문</script>'), ['<script>원문</script>'])
  })
})
