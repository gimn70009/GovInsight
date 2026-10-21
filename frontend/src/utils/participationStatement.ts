import type { ParticipationCriterionEntry } from '../api/types'

/** Never reconstruct or finish a sentence in the browser. */
export function participationStatement(entry: ParticipationCriterionEntry): string {
  return entry.displayStatus === 'VERIFIED' ? entry.rule.text : ''
}
